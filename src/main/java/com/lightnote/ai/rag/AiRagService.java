package com.lightnote.ai.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightnote.config.AiFeatureProperties;
import com.lightnote.dto.Result;
import com.lightnote.entity.AiKnowledge;
import com.lightnote.entity.Shop;
import com.lightnote.entity.ShopType;
import com.lightnote.entity.Voucher;
import com.lightnote.mapper.AiKnowledgeMapper;
import com.lightnote.service.IShopService;
import com.lightnote.service.IShopTypeService;
import com.lightnote.service.IVoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
public class AiRagService {

    private final AiKnowledgeMapper aiKnowledgeMapper;
    private final IShopService shopService;
    private final IShopTypeService shopTypeService;
    private final IVoucherService voucherService;
    private final LocalVectorizer localVectorizer;
    private final ObjectMapper objectMapper;
    private final AiFeatureProperties aiFeatureProperties;

    public String buildContext(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "";
        }
        ensureKnowledgeBase();
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
    }

    public Result rebuildKnowledgeBase() {
        aiKnowledgeMapper.delete(null);

        Map<Long, String> shopTypeMap = shopTypeService.list().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(ShopType::getId, ShopType::getName, (left, right) -> left, LinkedHashMap::new));

        Map<Long, List<Voucher>> voucherMap = new HashMap<>();
        for (Voucher voucher : voucherService.list()) {
            voucherMap.computeIfAbsent(voucher.getShopId(), key -> new ArrayList<>()).add(voucher);
        }

        LocalDateTime now = LocalDateTime.now();
        for (Shop shop : shopService.list()) {
            String content = buildShopKnowledgeContent(shop, shopTypeMap.get(shop.getTypeId()), voucherMap.get(shop.getId()));
            AiKnowledge knowledge = new AiKnowledge()
                    .setTitle(shop.getName())
                    .setContent(content)
                    .setSourceType("shop")
                    .setSourceId(shop.getId())
                    .setEmbedding(writeEmbedding(localVectorizer.embed(content)))
                    .setScore(null)
                    .setCreateTime(now)
                    .setUpdateTime(now);
            aiKnowledgeMapper.insert(knowledge);
        }

        return Result.ok("知识库重建完成，共写入 " + aiKnowledgeMapper.selectCount(null) + " 条记录");
    }

    public Result searchKnowledge(String query, Integer topK) {
        ensureKnowledgeBase();
        return Result.ok(searchKnowledgeInternal(query, topK == null ? aiFeatureProperties.getRag().getTopK() : topK));
    }

    private List<Map<String, Object>> searchKnowledgeInternal(String query, int topK) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        List<Double> queryVector = localVectorizer.embed(query);
        List<AiKnowledge> allKnowledge = aiKnowledgeMapper.selectList(new LambdaQueryWrapper<>());
        return allKnowledge.stream()
                .map(knowledge -> toSearchHit(knowledge, queryVector, query))
                .filter(hit -> ((Double) hit.get("score")) > 0.05D)
                .sorted(Comparator.comparing(item -> (Double) item.get("score"), Comparator.reverseOrder()))
                .limit(Math.max(1, topK))
                .collect(Collectors.toList());
    }

    private Map<String, Object> toSearchHit(AiKnowledge knowledge, List<Double> queryVector, String query) {
        List<Double> docVector = readEmbedding(knowledge.getEmbedding());
        double cosineScore = localVectorizer.cosine(queryVector, docVector);
        double lexicalScore = lexicalOverlapScore(query, knowledge.getTitle() + " " + knowledge.getContent());
        double finalScore = cosineScore * 0.7D + lexicalScore * 0.3D;

        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("id", knowledge.getId());
        hit.put("title", knowledge.getTitle());
        hit.put("content", knowledge.getContent());
        hit.put("sourceType", knowledge.getSourceType());
        hit.put("sourceId", knowledge.getSourceId());
        hit.put("score", finalScore);
        return hit;
    }

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

    private void ensureKnowledgeBase() {
        Long count = aiKnowledgeMapper.selectCount(new LambdaQueryWrapper<>());
        if (count != null && count > 0) {
            return;
        }
        if (aiFeatureProperties.getRag().isAutoRebuildOnEmpty()) {
            rebuildKnowledgeBase();
        }
    }

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

    private String writeEmbedding(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Double> readEmbedding(String embedding) {
        try {
            return objectMapper.readValue(embedding, new TypeReference<List<Double>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
