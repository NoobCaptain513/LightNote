package com.lightnote.ai.provider.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightnote.ai.model.AgentIntent;
import com.lightnote.ai.tool.ShopAgentToolExecutor;
import com.lightnote.dto.AgentReply;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SpringAiShopToolFactory {

    private final ShopAgentToolExecutor shopAgentToolExecutor;
    private final ObjectMapper objectMapper;

    /**
     * 为单次 Spring AI Agent 调用创建工具对象，并共享本轮店铺卡片收集容器。
     *
     * @param intent 当前用户意图
     * @param collectedShopMap 本轮工具调用收集到的店铺卡片
     * @return 带有 Spring AI @Tool 方法的工具对象
     */
    public SpringAiShopTools create(AgentIntent intent, Map<Long, AgentReply.ShopCard> collectedShopMap) {
        return new SpringAiShopTools(intent, collectedShopMap);
    }

    public final class SpringAiShopTools {
        private final AgentIntent intent;
        private final Map<Long, AgentReply.ShopCard> collectedShopMap;

        private SpringAiShopTools(AgentIntent intent, Map<Long, AgentReply.ShopCard> collectedShopMap) {
            this.intent = intent;
            this.collectedShopMap = collectedShopMap;
        }

        /**
         * 根据关键词搜索店铺，并把结构化搜索结果序列化为 Spring AI 工具可返回的 JSON 字符串。
         *
         * @param keyword 搜索关键词
         * @param sortBy 排序方式
         * @param x 用户经度
         * @param y 用户纬度
         * @return 店铺搜索结果 JSON；异常时返回空数组
         */
        @Tool(description = "根据关键词搜索店铺，支持按评分或距离排序")
        public String searchShop(
                @ToolParam(description = "搜索关键词") String keyword,
                @ToolParam(description = "排序方式") String sortBy,
                @ToolParam(description = "用户经度") Double x,
                @ToolParam(description = "用户纬度") Double y) {
            try {
                List<Map<String, Object>> result = shopAgentToolExecutor.searchShop(keyword, sortBy, x, y, intent, collectedShopMap);
                return objectMapper.writeValueAsString(result);
            } catch (Exception e) {
                return "[]";
            }
        }

        /**
         * 查询指定店铺的优惠券，并把结果序列化为 JSON 字符串返回给模型。
         *
         * @param shopId 店铺 ID
         * @return 优惠券列表 JSON；异常时返回空数组
         */
        @Tool(description = "查询指定店铺的优惠券")
        public String getVoucher(@ToolParam(description = "店铺ID") Long shopId) {
            try {
                List<Map<String, Object>> vouchers = shopAgentToolExecutor.getVoucher(shopId, collectedShopMap);
                return objectMapper.writeValueAsString(vouchers);
            } catch (Exception e) {
                return "[]";
            }
        }

        /**
         * 查询指定店铺详情，并把结果序列化为 JSON 字符串返回给模型。
         *
         * @param shopId 店铺 ID
         * @return 店铺详情 JSON；异常时返回空对象
         */
        @Tool(description = "查询店铺详情")
        public String getShopDetail(@ToolParam(description = "店铺ID") Long shopId) {
            try {
                Map<String, Object> shopMap = shopAgentToolExecutor.getShopDetail(shopId, collectedShopMap);
                return objectMapper.writeValueAsString(shopMap);
            } catch (Exception e) {
                return "{}";
            }
        }
    }
}
