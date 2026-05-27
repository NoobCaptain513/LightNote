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

        @Tool(description = "查询指定店铺的优惠券")
        public String getVoucher(@ToolParam(description = "店铺ID") Long shopId) {
            try {
                List<Map<String, Object>> vouchers = shopAgentToolExecutor.getVoucher(shopId, collectedShopMap);
                return objectMapper.writeValueAsString(vouchers);
            } catch (Exception e) {
                return "[]";
            }
        }

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
