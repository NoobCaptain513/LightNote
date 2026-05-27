package com.lightnote.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightnote.ai.embedding.EmbeddingClient;
import com.lightnote.config.AiEmbeddingProperties;
import com.lightnote.config.AiFeatureProperties;
import com.lightnote.config.PgVectorProperties;
import com.lightnote.dto.Result;
import com.lightnote.entity.Shop;
import com.lightnote.entity.ShopType;
import com.lightnote.entity.Voucher;
import com.lightnote.service.IShopService;
import com.lightnote.service.IShopTypeService;
import com.lightnote.service.IVoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiRagService {

    private final IShopService shopService;
    private final IShopTypeService shopTypeService;
    private final IVoucherService voucherService;
    private final EmbeddingClient embeddingClient;
    private final PgVectorKnowledgeRepository pgVectorKnowledgeRepository;
    private final ObjectMapper objectMapper;
    private final AiFeatureProperties aiFeatureProperties;
    private final AiEmbeddingProperties aiEmbeddingProperties;
    private final PgVectorProperties pgVectorProperties;

    /**
     * 构建上下文回复
     * @param query 查询文本
     * @return 包含上下文的回复
     */
    public String buildContext(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "";
        }
        try {
            // 确保知识库已建立
            ensureKnowledgeBase();
            // 搜索知识库
            List<Map<String, Object>> hits = searchKnowledgeInternal(query, aiFeatureProperties.getRag().getTopK());
            if (hits.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder("以下是从知识库检索到的参考信息，请优先基于这些信息回答：");
            for (int i = 0; i < hits.size(); i++) {
                Map<String, Object> hit = hits.get(i);
                builder.append("\n[参考")
                        .append(i + 1)
                        .append("] 标题：")
                        .append(hit.get("title"))
                        .append("；内容：")
                        .append(hit.get("content"));
            }
            return builder.toString();
        } catch (Exception e) {
            log.warn("RAG上下文构建失败，已降级为空上下文", e);
            return "";
        }
    }

    /**
     * 重建知识库
     *
     * @return 包含重建结果的Result对象
     */
    public Result rebuildKnowledgeBase() {
        try {
            Map<Long, String> shopTypeMap = shopTypeService.list().stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(ShopType::getId, ShopType::getName, (left, right) -> left, LinkedHashMap::new));

            Map<Long, List<Voucher>> voucherMap = new HashMap<>();
            for (Voucher voucher : voucherService.list()) {
                voucherMap.computeIfAbsent(voucher.getShopId(), key -> new ArrayList<>()).add(voucher);
            }

            List<KnowledgeChunk> chunks = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (Shop shop : shopService.list()) {
                String shopTypeName = shopTypeMap.get(shop.getTypeId());
                List<Voucher> vouchers = voucherMap.get(shop.getId());
                String content = buildShopKnowledgeContent(shop, shopTypeName, vouchers);
                chunks.add(new KnowledgeChunk()
                        .setTitle(shop.getName())
                        .setContent(content)
                        .setSourceType("shop")
                        .setSourceId(shop.getId())
                        .setChunkNo(0)
                        .setMetadataJson(buildShopMetadata(shop, shopTypeName, vouchers, now))
                        .setEmbeddingModel(embeddingClient.model())
                        .setContentHash(sha256(content)));
            }

            int batchSize = Math.max(1, aiEmbeddingProperties.getBatchSize());
            for (int start = 0; start < chunks.size(); start += batchSize) {
                int end = Math.min(start + batchSize, chunks.size());
                List<KnowledgeChunk> batch = chunks.subList(start, end);
                List<String> texts = batch.stream().map(KnowledgeChunk::getContent).collect(Collectors.toList());
                List<float[]> embeddings = embeddingClient.embed(texts);
                for (int i = 0; i < batch.size(); i++) {
                    batch.get(i).setEmbedding(embeddings.get(i));
                }
                pgVectorKnowledgeRepository.upsertAll(batch);
            }

            List<Long> shopIds = chunks.stream()
                    .map(KnowledgeChunk::getSourceId)
                    .collect(Collectors.toList());
            pgVectorKnowledgeRepository.deleteStaleSource("shop", embeddingClient.model(), shopIds);

            return Result.ok("知识库重建完成，共写入 " + pgVectorKnowledgeRepository.count(embeddingClient.model()) + " 条记录");
        } catch (Exception e) {
            log.error("RAG知识库重建失败", e);
            return Result.fail("RAG知识库重建失败: " + e.getMessage());
        }
    }

    /**
     * 搜索知识库
     * @param query 查询文本
     * @param topK 返回的TopK结果
     * @return 包含搜索结果的Result对象
     */
    public Result searchKnowledge(String query, Integer topK) {
        try {
            ensureKnowledgeBase();
            return Result.ok(searchKnowledgeHits(query, topK == null ? aiFeatureProperties.getRag().getTopK() : topK));
        } catch (Exception e) {
            log.error("RAG知识库搜索失败", e);
            return Result.fail("RAG知识库搜索失败: " + e.getMessage());
        }
    }

    /**
     * 返回结构化检索命中，供 Spring AI Advisor、LangChain4j Retriever 或 API 复用。
     *
     * @param query 查询文本
     * @param topK 返回数量
     * @return 结构化命中列表
     */
    public List<Map<String, Object>> searchKnowledgeHits(String query, int topK) throws Exception {
        ensureKnowledgeBase();
        return searchKnowledgeInternal(query, topK);
    }

    /**
     * 把用户问题转成向量，然后去 pgvector 知识库里找相似内容，最后过滤、排序、返回 topK 条结果
     *
     * @param query 查询文本
     * @param topK 返回的TopK结果
     * @return 包含搜索结果的列表
     */
    private List<Map<String, Object>> searchKnowledgeInternal(String query, int topK) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        float[] queryVector = embeddingClient.embedOne(query);

        int candidateK = Math.max(topK, pgVectorProperties.getCandidateK());
        return pgVectorKnowledgeRepository.search(queryVector, embeddingClient.model(), candidateK).stream()
                .map(hit -> toSearchHit(hit, query))
                .filter(hit -> ((Double) hit.get("score")) > pgVectorProperties.getMinScore())
                .sorted(Comparator.comparing(item -> (Double) item.get("score"), Comparator.reverseOrder()))
                .limit(Math.max(1, topK))
                .collect(Collectors.toList());
    }

    /**
     * 把 pgvector 返回的 RagHit 转成上层更好用的 Map，同时计算最终综合分数
     *
     * @param ragHit 知识库记录
     * @param query 查询文本
     * @return 包含搜索结果的Map
     */
    private Map<String, Object> toSearchHit(RagHit ragHit, String query) {
        double vectorScore = ragHit.getScore();
        double lexicalScore = lexicalOverlapScore(query, ragHit.getTitle() + " " + ragHit.getContent());
        double finalScore = vectorScore * 0.85D + lexicalScore * 0.15D;

        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("id", ragHit.getId());
        hit.put("title", ragHit.getTitle());
        hit.put("content", ragHit.getContent());
        hit.put("sourceType", ragHit.getSourceType());
        hit.put("sourceId", ragHit.getSourceId());
        hit.put("score", finalScore);
        hit.put("vectorScore", vectorScore);
        hit.put("lexicalScore", lexicalScore);
        return hit;
    }

    /**
     * 计算用户问题 query 和知识文本 text 在字符层面的重叠程度。
     *
     * @param query 查询文本
     * @param text 文本内容
     * @return 词汇重叠分数
     */
    private double lexicalOverlapScore(String query, String text) {
        if (query == null || text == null || query.isEmpty() || text.isEmpty()) {
            return 0D;
        }
        int matched = 0;
        for (int i = 0; i < query.length(); i++) {
            String character = String.valueOf(query.charAt(i));
            if (!character.trim().isEmpty() && text.contains(character)) {
                matched++;
            }
        }
        return matched == 0 ? 0D : (double) matched / Math.max(1, query.length());
    }

    /**
     * 确保知识库非空（内部方法）
     */
    private void ensureKnowledgeBase() {
        long count = pgVectorKnowledgeRepository.count(embeddingClient.model());
        if (count > 0) {
            return;
        }
        if (aiFeatureProperties.getRag().isAutoRebuildOnEmpty()) {
            Result result = rebuildKnowledgeBase();
            if (!Boolean.TRUE.equals(result.getSuccess())) {
                throw new IllegalStateException(result.getErrorMsg());
            }
        }
    }

    /**
     * 构建店铺知识库内容（内部方法）
     * @param shop 店铺对象
     * @param shopTypeName 店铺类型名称
     * @param vouchers 店铺优惠券列表
     * @return 店铺知识库内容
     */
    private String buildShopKnowledgeContent(Shop shop, String shopTypeName, List<Voucher> vouchers) {
        StringBuilder builder = new StringBuilder();
        builder.append("店铺名称：").append(shop.getName());
        if (shopTypeName != null) {
            builder.append("；类型：").append(shopTypeName);
        }
        if (shop.getArea() != null) {
            builder.append("；商圈：").append(shop.getArea());
        }
        if (shop.getAddress() != null) {
            builder.append("；地址：").append(shop.getAddress());
        }
        if (shop.getAvgPrice() != null) {
            builder.append("；人均：").append(shop.getAvgPrice()).append("元");
        }
        if (shop.getScore() != null) {
            builder.append("；评分：").append(shop.getScore() / 10.0);
        }
        if (shop.getOpenHours() != null) {
            builder.append("；营业时间：").append(shop.getOpenHours());
        }
        if (vouchers != null && !vouchers.isEmpty()) {
            builder.append("；优惠：");
            for (Voucher voucher : vouchers) {
                builder.append(voucher.getTitle());
                if (voucher.getSubTitle() != null) {
                    builder.append("(").append(voucher.getSubTitle()).append(")");
                }
                builder.append("；");
            }
        }
        return builder.toString();
    }


    private String buildShopMetadata(Shop shop, String shopTypeName, List<Voucher> vouchers, LocalDateTime rebuildTime) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("shopType", shopTypeName);
            metadata.put("area", shop.getArea());
            metadata.put("address", shop.getAddress());
            metadata.put("avgPrice", shop.getAvgPrice());
            metadata.put("score", shop.getScore());
            metadata.put("openHours", shop.getOpenHours());
            metadata.put("rebuildTime", rebuildTime.toString());
            if (vouchers != null && !vouchers.isEmpty()) {
                metadata.put("vouchers", vouchers.stream()
                        .map(voucher -> {
                            Map<String, Object> voucherMap = new LinkedHashMap<>();
                            voucherMap.put("id", voucher.getId());
                            voucherMap.put("title", voucher.getTitle());
                            voucherMap.put("subTitle", voucher.getSubTitle());
                            return voucherMap;
                        })
                        .collect(Collectors.toList()));
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算内容Hash失败", e);
        }
    }
}
