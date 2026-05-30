package com.lightnote.ai.rag;

import com.lightnote.ai.model.AgentIntent;
import com.lightnote.ai.tool.ShopAgentToolExecutor;
import com.lightnote.dto.AgentReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRagCandidateService {

    private static final int MAX_AGENT_RAG_CANDIDATES = 5;
    private static final String SHOP_SOURCE_TYPE = "shop";

    private final AiRagService aiRagService;
    private final ShopAgentToolExecutor shopAgentToolExecutor;

    /**
     * 提前用 RAG 召回候选店铺，并通过业务详情工具把 sourceId 落成真实店铺数据。
     *
     * @param query 当前用户输入
     * @param intent 当前 Agent 意图
     * @return 候选店铺提示和可兜底合并的店铺卡片
     */
    public AgentRagCandidates preloadShopCandidates(String query, AgentIntent intent) {
        if (!StringUtils.hasText(query)) {
            return AgentRagCandidates.empty();
        }

        Map<Long, AgentReply.ShopCard> candidateShopMap = new LinkedHashMap<>();
        List<Map<String, Object>> candidateShopDetails = new ArrayList<>();
        Set<Long> loadedShopIds = new LinkedHashSet<>();

        try {
            List<Map<String, Object>> hits = aiRagService.searchKnowledgeHits(query, MAX_AGENT_RAG_CANDIDATES);
            for (Map<String, Object> hit : hits) {
                if (!SHOP_SOURCE_TYPE.equals(String.valueOf(hit.get("sourceType")))) {
                    continue;
                }
                Long shopId = readLong(hit.get("sourceId"));
                if (shopId == null || !loadedShopIds.add(shopId)) {
                    continue;
                }
                Map<String, Object> shopMap = shopAgentToolExecutor.getShopDetail(shopId, candidateShopMap);
                if (shopMap == null || shopMap.isEmpty()) {
                    continue;
                }
                candidateShopDetails.add(withRagScore(shopMap, hit.get("score")));
                if (candidateShopDetails.size() >= MAX_AGENT_RAG_CANDIDATES) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Agent RAG候选店铺召回失败，已跳过候选增强", e);
            return AgentRagCandidates.empty();
        }

        if (candidateShopMap.isEmpty()) {
            return AgentRagCandidates.empty();
        }
        return new AgentRagCandidates(buildCandidatePrompt(candidateShopDetails, intent), candidateShopMap);
    }

    /**
     * 当模型没有通过工具收集到店铺卡片时，把前置 RAG 候选卡片合并为兜底结果。
     *
     * @param targetShopMap Agent 本轮已收集的店铺卡片
     * @param candidates 前置 RAG 候选结果
     */
    public void mergeCandidatesIfEmpty(Map<Long, AgentReply.ShopCard> targetShopMap,
                                       AgentRagCandidates candidates) {
        if (targetShopMap == null || !targetShopMap.isEmpty() || candidates == null || !candidates.hasCandidates()) {
            return;
        }
        targetShopMap.putAll(candidates.shopCards());
    }

    /**
     * 构建可追加到 Agent system prompt 的候选店铺摘要。
     *
     * @param candidateShopDetails 已通过业务详情接口回查的候选店铺
     * @param intent 当前 Agent 意图
     * @return 候选店铺提示文本
     */
    private String buildCandidatePrompt(List<Map<String, Object>> candidateShopDetails, AgentIntent intent) {
        if (candidateShopDetails == null || candidateShopDetails.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\nRAG候选店铺已通过业务详情接口回查，可作为本轮推荐候选。")
                .append("如果候选符合用户需求，优先围绕这些真实店铺组织回答；")
                .append("如需更精确排序、距离或优惠券，继续调用工具补充查询。");
        appendIntentGuidance(builder, intent);
        for (Map<String, Object> shopMap : candidateShopDetails) {
            builder.append("\n- shopId=").append(textValue(shopMap.get("id")))
                    .append("，名称=").append(textValue(shopMap.get("name")))
                    .append("，区域=").append(textValue(shopMap.get("area")))
                    .append("，地址=").append(textValue(shopMap.get("address")))
                    .append("，评分=").append(textValue(shopMap.get("score")))
                    .append("，人均=").append(textValue(shopMap.get("avgPrice")))
                    .append("，营业时间=").append(textValue(shopMap.get("openHours")));
            if (shopMap.get("ragScore") != null) {
                builder.append("，召回分=").append(textValue(shopMap.get("ragScore")));
            }
        }
        return builder.toString();
    }

    /**
     * 根据 Agent 意图补充候选店铺的工具调用指引。
     *
     * @param builder 候选提示构建器
     * @param intent 当前 Agent 意图
     */
    private void appendIntentGuidance(StringBuilder builder, AgentIntent intent) {
        if (intent == null) {
            return;
        }
        if (intent.isNeedVoucher()) {
            builder.append("本轮关注优惠券，候选店铺确认后优先调用优惠券工具补充券信息。");
        }
        if (intent.isSortByDistance()) {
            builder.append("本轮关注距离，若有用户定位，优先调用搜索工具按距离重新排序。");
        }
        if (intent.isSortByScore()) {
            builder.append("本轮关注评分，候选店铺回答时优先按评分高低组织。");
        }
    }

    /**
     * 复制店铺详情并附加 RAG 召回分，避免污染工具原始返回对象。
     *
     * @param shopMap 店铺详情
     * @param ragScore RAG 综合召回分
     * @return 带召回分的候选店铺详情
     */
    private Map<String, Object> withRagScore(Map<String, Object> shopMap, Object ragScore) {
        Map<String, Object> candidate = new LinkedHashMap<>(shopMap);
        if (ragScore instanceof Number) {
            candidate.put("ragScore", ragScore);
        }
        return candidate;
    }

    /**
     * 把 RAG 命中结果中的 sourceId 转成 Long，兼容数值和字符串两种来源。
     *
     * @param value 待转换值
     * @return Long 值；无法转换时返回 null
     */
    private Long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /**
     * 将候选字段转成适合放入提示词的文本。
     *
     * @param value 候选字段值
     * @return 字段文本；空值返回“未知”
     */
    private String textValue(Object value) {
        return value == null ? "未知" : String.valueOf(value);
    }

    public record AgentRagCandidates(String prompt, Map<Long, AgentReply.ShopCard> shopCards) {

        /**
         * 返回空候选结果。
         *
         * @return 空候选结果
         */
        public static AgentRagCandidates empty() {
            return new AgentRagCandidates("", new LinkedHashMap<>());
        }

        /**
         * 判断是否召回到了可用于兜底的店铺卡片。
         *
         * @return 有候选卡片时返回 true
         */
        public boolean hasCandidates() {
            return shopCards != null && !shopCards.isEmpty();
        }
    }
}
