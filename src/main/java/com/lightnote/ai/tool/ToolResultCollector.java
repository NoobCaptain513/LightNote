package com.lightnote.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightnote.ai.reply.ShopCardAssembler;
import com.lightnote.dto.AgentReply;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ToolResultCollector {

    private final ObjectMapper objectMapper;
    private final ShopCardAssembler shopCardAssembler;

    public void collect(String toolName, String toolResult, Map<Long, AgentReply.ShopCard> collectedShopMap) {
        try {
            if ("searchShop".equals(toolName)) {
                List<Map<String, Object>> shopList = objectMapper.readValue(toolResult, List.class);
                for (Map<String, Object> shopMap : shopList) {
                    shopCardAssembler.mergeShopCard(collectedShopMap, shopMap);
                }
                return;
            }

            if ("getShopDetail".equals(toolName)) {
                Map<String, Object> shopMap = objectMapper.readValue(toolResult, Map.class);
                shopCardAssembler.mergeShopCard(collectedShopMap, shopMap);
                return;
            }

            if ("getVoucher".equals(toolName)) {
                List<Map<String, Object>> voucherList = objectMapper.readValue(toolResult, List.class);
                shopCardAssembler.mergeVoucherCards(collectedShopMap, voucherList);
            }
        } catch (Exception ignored) {
        }
    }
}
