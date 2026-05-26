package com.lightnote.ai.tool;

import com.lightnote.ai.reply.ShopCardAssembler;
import com.lightnote.dto.AgentReply;
import com.lightnote.dto.Result;
import com.lightnote.entity.Shop;
import com.lightnote.entity.ShopType;
import com.lightnote.entity.Voucher;
import com.lightnote.service.IShopService;
import com.lightnote.service.IShopTypeService;
import com.lightnote.service.IVoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ShopAgentToolService {

    private static final double MAX_DISTANCE_METERS = 10_000D;

    private final IShopService shopService;
    private final IShopTypeService shopTypeService;
    private final IVoucherService voucherService;
    private final ShopCardAssembler shopCardAssembler;
    private final ShopTypeAliasesProperties shopTypeAliasesProperties;

    /**
     * 搜索商店（按名称或类型）
     *
     * @param keyword 搜索关键词（可以是商店名称或类型）
     * @param sortBy  排序字段（如"distance"或"rating"）
     * @param x       用户经度
     * @param y       用户纬度
     * @return 包含商店映射的列表（每个商店包含名称、类型、距离、评分、优惠券等信息）
     */
    public List<Map<String, Object>> searchShop(String keyword, String sortBy, Double x, Double y) {
        List<Shop> shops = searchShopsByKeywordOrType(keyword, sortBy, x, y);
        List<Map<String, Object>> results = new ArrayList<>(shops.size());
        for (Shop shop : shops) {
            results.add(shopCardAssembler.toShopMap(shop));
        }
        return results;
    }

    /**
     * 获取商店优惠券
     *
     * @param shopId 商店ID
     * @return 包含优惠券映射的列表（每个优惠券包含标题、描述、金额等信息）
     */
    public List<Map<String, Object>> getVoucher(Long shopId) {
        if (shopId == null) {
            return new ArrayList<>();
        }
        Result voucherResult = voucherService.queryVoucherOfShop(shopId);
        Object data = voucherResult.getData();
        List<Map<String, Object>> vouchers = new ArrayList<>();
        if (!(data instanceof List<?> list)) {
            return vouchers;
        }
        for (Object item : list) {
            if (item instanceof Voucher voucher) {
                vouchers.add(shopCardAssembler.toVoucherMap(voucher));
            } else if (item instanceof Map<?, ?> map) {
                vouchers.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return vouchers;
    }

    /**
     * 获取商店详细信息
     *
     * @param shopId 商店ID
     * @return 包含商店详细信息的映射（包含名称、类型、地址、评分、优惠券等）
     */
    public Map<String, Object> getShopDetail(Long shopId) {
        if (shopId == null) {
            return new LinkedHashMap<>();
        }
        Shop shop = shopService.getById(shopId);
        if (shop == null) {
            return new LinkedHashMap<>();
        }
        return shopCardAssembler.toShopMap(shop);
    }

    /**
     * 为商店卡片添加优惠券信息
     *
     * @param shops 商店卡片列表
     */
    public void enrichShopCardsWithVouchers(List<AgentReply.ShopCard> shops) {
        for (AgentReply.ShopCard shop : shops) {
            // 如果商店已经添加过优惠券信息，则跳过
            if (shop.getId() == null || shop.getVoucherTitle() != null) {
                continue;
            }
            Result voucherResult = voucherService.queryVoucherOfShop(shop.getId());
            Object data = voucherResult.getData();
            if (!(data instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            Object first = list.get(0);
            // 如果优惠券数据格式不正确，则跳过
            if (!(first instanceof Voucher voucher)) {
                continue;
            }
            shop.setVoucherTitle(voucher.getTitle());
            shop.setVoucherDesc(voucher.getSubTitle());
        }
    }

    /**
     * 根据关键词或类型搜索商店
     *
     * @param keyword 关键词（可以是商店名称或类型）
     * @param sortBy 排序字段（如"distance"或"rating"）
     * @param x 用户经度
     * @param y 用户纬度
     * @return 匹配的商店列表
     */
    private List<Shop> searchShopsByKeywordOrType(String keyword, String sortBy, Double x, Double y) {
        String trimmedKeyword = keyword == null ? "" : keyword.trim();
        if (trimmedKeyword.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> matchedTypeIds = resolveMatchedShopTypeIds(trimmedKeyword);
        List<Shop> shops = shopService.query()
                .and(wrapper -> {
                    boolean hasCondition = false;
                    if (!matchedTypeIds.isEmpty()) {
                        wrapper.in("type_id", matchedTypeIds);
                        hasCondition = true;
                    }
                    if (!trimmedKeyword.isEmpty()) {
                        if (hasCondition) {
                            wrapper.or();
                        }
                        wrapper.like("name", trimmedKeyword)
                                .or().like("area", trimmedKeyword)
                                .or().like("address", trimmedKeyword);
                    }
                })
                .last("limit 20")
                .list();
        applyShopSorting(shops, sortBy, x, y);
        return shops;
    }

    /**
     * 应用商店排序策略
     *
     * @param shops 商店列表
     * @param sortBy 排序方式（"distance_asc"或"score_desc"）
     * @param x 用户经度
     * @param y 用户纬度
     */
    private void applyShopSorting(List<Shop> shops, String sortBy, Double x, Double y) {
        if (x != null && y != null) {
            for (Shop shop : shops) {
                if (shop.getX() != null && shop.getY() != null) {
                    shop.setDistance(calculateDistanceMeters(x, y, shop.getX(), shop.getY()));
                }
            }
        }

        if ("distance_asc".equals(sortBy) && x != null && y != null) {
            shops.sort(Comparator.comparing(item -> item.getDistance() == null ? Double.MAX_VALUE : item.getDistance()));

            boolean hasNearbyShop = shops.stream()
                    .anyMatch(shop -> shop.getDistance() != null && shop.getDistance() <= MAX_DISTANCE_METERS);
            if (hasNearbyShop) {
                shops.removeIf(shop -> shop.getDistance() != null && shop.getDistance() > MAX_DISTANCE_METERS);
            }
        } else if ("score_desc".equals(sortBy)) {
            shops.sort(Comparator.comparing((Shop item) -> item.getScore() == null ? Integer.MIN_VALUE : item.getScore()).reversed());
        }

        if (shops.size() > 5) {
            shops.subList(5, shops.size()).clear();
        }
    }

    /**
     * 解析匹配的商店类型ID（根据关键词）
     *
     * @param keyword 关键词（可以是商店类型名称或别名）
     * @return 匹配的商店类型ID列表
     */
    private List<Long> resolveMatchedShopTypeIds(String keyword){
        String normalizedKeyword = normalizeTypeText(keyword);
        if (normalizedKeyword.isEmpty()) {
            return new ArrayList<>();
        }

        Set<Long> matchedTypeIds = new HashSet<>();
        List<ShopType> shopTypes = shopTypeService.list();
        for (ShopType shopType : shopTypes) {
            if (shopType == null || shopType.getId() == null || shopType.getName() == null) {
                continue;
            }
            String normalizedTypeName = normalizeTypeText(shopType.getName());
            if (normalizedTypeName.isEmpty()) {
                continue;
            }
            if (normalizedKeyword.contains(normalizedTypeName)
                    || normalizedTypeName.contains(normalizedKeyword)
                    || matchTypeAlias(normalizedKeyword, normalizedTypeName)) {
                matchedTypeIds.add(shopType.getId());
            }
        }
        return new ArrayList<>(matchedTypeIds);
    }

    /**
     * 匹配商店类型别名（根据关键词）
     *
     * @param normalizedKeyword 已归一化的关键词
     * @param normalizedTypeName 已归一化的商店类型名称
     * @return 如果匹配成功则返回true，否则返回false
     */
    private boolean matchTypeAlias(String normalizedKeyword, String normalizedTypeName) {
        for (String alias : getAliases(normalizedTypeName)) {
            String normalizedAlias = normalizeTypeText(alias);
            if (normalizedAlias.isEmpty()) {
                continue;
            }
            if (normalizedKeyword.contains(normalizedAlias) || normalizedAlias.contains(normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取商店类型的别名列表（根据商店类型名称）
     *
     * @param normalizedTypeName 已归一化的商店类型名称
     * @return 商店类型的别名列表
     */
    private List<String> getAliases(String normalizedTypeName) {
        Map<String, List<String>> configuredAliases = shopTypeAliasesProperties.getTypeAliases();
        if (configuredAliases != null && !configuredAliases.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : configuredAliases.entrySet()) {
                if (normalizedTypeName.equals(normalizeTypeText(entry.getKey()))) {
                    return entry.getValue();
                }
            }
        }
        return Collections.emptyList();
    }

    /**
     * 归一化商店类型文本（移除特殊字符和空格）
     *
     * @param text 商店类型文本
     * @return 归一化后的文本
     */
    private String normalizeTypeText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("·", "")
                .replace(".", "")
                .replace("，", "")
                .replace(",", "")
                .replace("、", "")
                .replace("（", "")
                .replace("）", "")
                .replace("(", "")
                .replace(")", "")
                .replace(" ", "")
                .toLowerCase()
                .trim();
    }

    /**
     * 计算两点之间的距离（米）
     *
     * @param x1 第一个点的经度
     * @param y1 第一个点的纬度
     * @param x2 第二个点的经度
     * @param y2 第二个点的纬度
     * @return 两点之间的距离（米）
     */
    private double calculateDistanceMeters(Double x1, Double y1, Double x2, Double y2) {
        double earthRadius = 6371000D;
        double lat1 = Math.toRadians(y1);
        double lat2 = Math.toRadians(y2);
        double latDelta = Math.toRadians(y2 - y1);
        double lngDelta = Math.toRadians(x2 - x1);
        double a = Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(lngDelta / 2) * Math.sin(lngDelta / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }
}
