package com.hmdp.ai.reply;

import com.hmdp.dto.AgentReply;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ShopCardAssembler {

    public Map<String, Object> toShopMap(Shop shop) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", shop.getId());
        result.put("name", shop.getName());
        result.put("area", shop.getArea());
        result.put("address", shop.getAddress());
        result.put("score", shop.getScore() != null ? shop.getScore() / 10.0 : null);
        result.put("avgPrice", shop.getAvgPrice());
        result.put("distance", shop.getDistance() != null ? String.format("%.0fm", shop.getDistance()) : null);
        result.put("openHours", shop.getOpenHours());
        return result;
    }

    public Map<String, Object> toVoucherMap(Voucher voucher) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", voucher.getShopId());
        result.put("title", voucher.getTitle());
        result.put("subTitle", voucher.getSubTitle());
        return result;
    }

    public void mergeShopCard(Map<Long, AgentReply.ShopCard> collectedShopMap, Map<String, Object> shopMap) {
        if (shopMap == null || !(shopMap.get("id") instanceof Number)) {
            return;
        }

        Long shopId = ((Number) shopMap.get("id")).longValue();
        AgentReply.ShopCard shopCard = collectedShopMap.getOrDefault(shopId, new AgentReply.ShopCard());
        shopCard.setId(shopId);
        if (shopMap.get("name") != null) {
            shopCard.setName(String.valueOf(shopMap.get("name")));
        }
        if (shopMap.get("area") != null) {
            shopCard.setArea(String.valueOf(shopMap.get("area")));
        }
        if (shopMap.get("address") != null) {
            shopCard.setAddress(String.valueOf(shopMap.get("address")));
        }
        if (shopMap.get("score") instanceof Number) {
            shopCard.setScore(((Number) shopMap.get("score")).doubleValue());
        }
        if (shopMap.get("avgPrice") instanceof Number) {
            shopCard.setAvgPrice(((Number) shopMap.get("avgPrice")).longValue());
        }
        if (shopMap.get("distance") != null) {
            shopCard.setDistance(String.valueOf(shopMap.get("distance")));
        }
        if (shopMap.get("openHours") != null) {
            shopCard.setOpenHours(String.valueOf(shopMap.get("openHours")));
        }
        collectedShopMap.put(shopId, shopCard);
    }

    public void mergeVoucherCards(Map<Long, AgentReply.ShopCard> collectedShopMap, List<Map<String, Object>> voucherList) {
        if (voucherList == null || voucherList.isEmpty()) {
            return;
        }
        for (Map<String, Object> voucherMap : voucherList) {
            if (!(voucherMap.get("shopId") instanceof Number)) {
                continue;
            }
            Long shopId = ((Number) voucherMap.get("shopId")).longValue();
            AgentReply.ShopCard shopCard = collectedShopMap.get(shopId);
            if (shopCard == null) {
                continue;
            }
            if (voucherMap.get("title") != null) {
                shopCard.setVoucherTitle(String.valueOf(voucherMap.get("title")));
            }
            if (voucherMap.get("subTitle") != null) {
                shopCard.setVoucherDesc(String.valueOf(voucherMap.get("subTitle")));
            }
        }
    }

    public List<AgentReply.ShopCard> toShopCardList(Map<Long, AgentReply.ShopCard> collectedShopMap) {
        return new ArrayList<>(collectedShopMap.values());
    }

    public AgentReply buildReply(String text, List<AgentReply.ShopCard> shops) {
        AgentReply reply = new AgentReply();
        reply.setText(text);
        reply.setShops(shops);
        return reply;
    }
}
