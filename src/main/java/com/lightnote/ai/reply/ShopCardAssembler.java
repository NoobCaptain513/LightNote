package com.lightnote.ai.reply;

import com.lightnote.dto.AgentReply;
import com.lightnote.entity.Shop;
import com.lightnote.entity.Voucher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
@Component
public class ShopCardAssembler {

    /**
     * 将Shop实体转换为Map，用于构建AgentReply.ShopCard
     * @param shop Shop实体
     * @return 包含Shop信息的Map
     */
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

    /**
     * 将Voucher实体转换为Map，用于构建AgentReply.ShopCard
     * @param voucher Voucher实体
     * @return 包含Voucher信息的Map
     */
    public Map<String, Object> toVoucherMap(Voucher voucher) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", voucher.getShopId());
        result.put("title", voucher.getTitle());
        result.put("subTitle", voucher.getSubTitle());
        return result;
    }

    /**
     * 合并ShopCard到collectedShopMap
     * @param collectedShopMap 已收集的ShopCard映射
     * @param shopMap 待合并的Shop信息Map
     */
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

    /**
     * 合并VoucherCard到collectedShopMap
     * @param collectedShopMap 已收集的ShopCard映射
     * @param voucherList 待合并的Voucher信息Map列表
     */
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

    /**
     * 构建AgentReply对象
     * @param text 回复文本
     * @param shops 包含ShopCard的列表
     * @return 构建好的AgentReply对象
     */
    public AgentReply buildReply(String text, List<AgentReply.ShopCard> shops) {
        AgentReply reply = new AgentReply();
        reply.setText(text);
        reply.setShops(shops);
        return reply;
    }
}
