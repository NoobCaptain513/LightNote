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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ShopAgentToolService {

    private static final double MAX_DISTANCE_METERS = 10_000D;

    private static final Map<String, List<String>> DEFAULT_TYPE_ALIASES = new HashMap<>();

    static {
        DEFAULT_TYPE_ALIASES.put("美食", Arrays.asList(
                "吃饭", "吃的", "餐厅", "饭店", "馆子", "美食", "小吃", "夜宵", "早餐", "下午茶",
                "咖啡", "奶茶", "甜品", "火锅", "烧烤", "烤肉", "自助", "海鲜", "寿司", "日料",
                "西餐", "中餐", "川菜", "湘菜", "粤菜", "面", "粉", "麻辣烫"
        ));
        DEFAULT_TYPE_ALIASES.put("ktv", Arrays.asList("ktv", "唱歌", "歌厅", "量贩ktv", "麦霸"));
        DEFAULT_TYPE_ALIASES.put("丽人美发", Arrays.asList("美发", "理发", "剪发", "剪头发", "做头发", "染发", "烫发", "发型"));
        DEFAULT_TYPE_ALIASES.put("健身运动", Arrays.asList("健身", "运动", "瑜伽", "羽毛球", "篮球", "游泳", "撸铁", "健身房"));
        DEFAULT_TYPE_ALIASES.put("按摩足疗", Arrays.asList("按摩", "足疗", "推拿", "采耳", "修脚", "足浴"));
        DEFAULT_TYPE_ALIASES.put("美容spa", Arrays.asList("美容", "spa", "护肤", "面部护理", "身体护理"));
        DEFAULT_TYPE_ALIASES.put("亲子游乐", Arrays.asList("亲子", "儿童乐园", "遛娃", "宝宝玩", "游乐场"));
        DEFAULT_TYPE_ALIASES.put("酒吧", Arrays.asList("酒吧", "清吧", "小酒馆", "夜店", "喝酒"));
        DEFAULT_TYPE_ALIASES.put("轰趴馆", Arrays.asList("轰趴", "聚会", "团建"));
        DEFAULT_TYPE_ALIASES.put("美睫美甲", Arrays.asList("美甲", "美睫", "做指甲", "指甲", "睫毛"));
    }

    private final IShopService shopService;
    private final IShopTypeService shopTypeService;
    private final IVoucherService voucherService;
    private final ShopCardAssembler shopCardAssembler;
    private final ShopTypeAliasesProperties shopTypeAliasesProperties;

    public List<Map<String, Object>> searchShop(String keyword, String sortBy, Double x, Double y) {
        List<Shop> shops = searchShopsByKeywordOrType(keyword, sortBy, x, y);
        List<Map<String, Object>> results = new ArrayList<>(shops.size());
        for (Shop shop : shops) {
            results.add(shopCardAssembler.toShopMap(shop));
        }
        return results;
    }

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

    public void enrichShopCardsWithVouchers(List<AgentReply.ShopCard> shops) {
        for (AgentReply.ShopCard shop : shops) {
            if (shop.getId() == null || shop.getVoucherTitle() != null) {
                continue;
            }
            Result voucherResult = voucherService.queryVoucherOfShop(shop.getId());
            Object data = voucherResult.getData();
            if (!(data instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            Object first = list.get(0);
            if (!(first instanceof Voucher voucher)) {
                continue;
            }
            shop.setVoucherTitle(voucher.getTitle());
            shop.setVoucherDesc(voucher.getSubTitle());
        }
    }

    @SuppressWarnings("unchecked")
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

    private List<Long> resolveMatchedShopTypeIds(String keyword) {
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

    private List<String> getAliases(String normalizedTypeName) {
        Map<String, List<String>> configuredAliases = shopTypeAliasesProperties.getTypeAliases();
        if (configuredAliases != null && !configuredAliases.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : configuredAliases.entrySet()) {
                if (normalizedTypeName.equals(normalizeTypeText(entry.getKey()))) {
                    return entry.getValue();
                }
            }
        }
        return DEFAULT_TYPE_ALIASES.getOrDefault(normalizedTypeName, Collections.emptyList());
    }

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
