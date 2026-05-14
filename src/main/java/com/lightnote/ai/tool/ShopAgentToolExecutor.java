package com.lightnote.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightnote.ai.intent.AgentIntentAnalyzer;
import com.lightnote.ai.model.AgentIntent;
import com.lightnote.ai.reply.ShopCardAssembler;
import com.lightnote.dto.AgentReply;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ShopAgentToolExecutor {

    private final ShopAgentToolService shopAgentToolService;
    private final ShopCardAssembler shopCardAssembler;
    private final AgentIntentAnalyzer intentAnalyzer;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> searchShop(String keyword,
                                                String sortBy,
                                                Double x,
                                                Double y,
                                                AgentIntent intent,
                                                Map<Long, AgentReply.ShopCard> collectedShopMap) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return List.of();
        }
        String resolvedSortBy = intentAnalyzer.resolveSortBy(sortBy, intent);
        Double resolvedX = x != null ? x : intent.getX();
        Double resolvedY = y != null ? y : intent.getY();
        List<Map<String, Object>> result = shopAgentToolService.searchShop(keyword, resolvedSortBy, resolvedX, resolvedY);
        for (Map<String, Object> shopMap : result) {
            shopCardAssembler.mergeShopCard(collectedShopMap, shopMap);
        }
        return result;
    }

    public List<Map<String, Object>> getVoucher(Long shopId,
                                                Map<Long, AgentReply.ShopCard> collectedShopMap) {
        List<Map<String, Object>> vouchers = shopAgentToolService.getVoucher(shopId);
        shopCardAssembler.mergeVoucherCards(collectedShopMap, vouchers);
        return vouchers;
    }

    public Map<String, Object> getShopDetail(Long shopId,
                                             Map<Long, AgentReply.ShopCard> collectedShopMap) {
        Map<String, Object> shopMap = shopAgentToolService.getShopDetail(shopId);
        if (shopMap != null) {
            shopCardAssembler.mergeShopCard(collectedShopMap, shopMap);
        }
        return shopMap;
    }

    public String executeNativeTool(String toolName,
                                    String inputJson,
                                    AgentIntent intent,
                                    Map<Long, AgentReply.ShopCard> collectedShopMap) {
        try {
            Map<String, Object> input = objectMapper.readValue(inputJson, Map.class);

            if ("searchShop".equals(toolName)) {
                List<Map<String, Object>> result = searchShop(
                        stringValue(input.get("keyword")),
                        stringValue(input.get("sortBy")),
                        readDouble(input.get("x")),
                        readDouble(input.get("y")),
                        intent,
                        collectedShopMap
                );
                return objectMapper.writeValueAsString(result);
            }

            if ("getVoucher".equals(toolName)) {
                Long shopId = readLong(input.get("shopId"));
                if (shopId == null) {
                    return errorJson("缺少店铺ID");
                }
                return objectMapper.writeValueAsString(getVoucher(shopId, collectedShopMap));
            }

            if ("getShopDetail".equals(toolName)) {
                Long shopId = readLong(input.get("shopId"));
                if (shopId == null) {
                    return errorJson("缺少店铺ID");
                }
                return objectMapper.writeValueAsString(getShopDetail(shopId, collectedShopMap));
            }
        } catch (Exception e) {
            return errorJson("工具执行失败: " + e.getMessage());
        }
        return errorJson("未知工具");
    }

    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception ignored) {
            return "{\"error\":\"" + message + "\"}";
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double readDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private Long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
