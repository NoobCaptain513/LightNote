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
//AI 工具调用的执行适配层
public class ShopAgentToolExecutor {

    private final ShopAgentToolService shopAgentToolService;
    private final ShopCardAssembler shopCardAssembler;
    private final AgentIntentAnalyzer intentAnalyzer;
    private final ObjectMapper objectMapper;

    /**
     * 执行店铺搜索工具
     * @param keyword 搜索关键词
     * @param sortBy 排序字段
     * @param x 经度
     * @param y 纬度
     * @param intent 意图
     * @param collectedShopMap 巶集的店铺卡片映射
     * @return 包含店铺卡片的列表
     */
    public List<Map<String, Object>> searchShop(String keyword,
                                                String sortBy,
                                                Double x,
                                                Double y,
                                                AgentIntent intent,
                                                Map<Long, AgentReply.ShopCard> collectedShopMap) {
        if (keyword == null || keyword.trim().isEmpty()) {
            // 返回空列表
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

    /**
     * 执行获取店铺优惠券工具
     * @param shopId 店铺ID
     * @param collectedShopMap 巶集的店铺卡片映射
     * @return 包含优惠券卡片的列表
     */
    public List<Map<String, Object>> getVoucher(Long shopId,
                                                Map<Long, AgentReply.ShopCard> collectedShopMap) {
        List<Map<String, Object>> vouchers = shopAgentToolService.getVoucher(shopId);
        shopCardAssembler.mergeVoucherCards(collectedShopMap, vouchers);
        return vouchers;
    }

    /**
     * 执行获取店铺详情工具
     * @param shopId 店铺ID
     * @param collectedShopMap 巶集的店铺卡片映射
     * @return 包含店铺详情的映射
     */
    public Map<String, Object> getShopDetail(Long shopId,
                                             Map<Long, AgentReply.ShopCard> collectedShopMap) {
        Map<String, Object> shopMap = shopAgentToolService.getShopDetail(shopId);
        if (shopMap != null) {
            shopCardAssembler.mergeShopCard(collectedShopMap, shopMap);
        }
        return shopMap;
    }

    /**
     * 执行本地工具
     * @param toolName 工具名称
     * @param inputJson 输入参数JSON字符串
     * @param intent 意图
     * @param collectedShopMap 巶集的店铺卡片映射
     * @return 包含工具执行结果的JSON字符串
     */
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

    /**
     * 生成错误JSON字符串
     * @param message 错误消息
     * @return 包含错误消息的JSON字符串
     */
    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception ignored) {
            return "{\"error\":\"" + message + "\"}";
        }
    }

    /**
     * 将对象转换为字符串
     * @param value 输入值
     * @return 转换后的字符串
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 将对象转换为Double
     * @param value 输入值
     * @return 转换后的Double值
     */
    private Double readDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    /**
     * 将对象转换为Long
     * @param value 输入值
     * @return 转换后的Long值
     */
    private Long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
