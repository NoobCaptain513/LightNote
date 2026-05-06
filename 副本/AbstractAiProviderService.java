package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiMessageDTO;
import com.hmdp.dto.AgentReply;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.AiMessage;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.AiMessageMapper;
import com.hmdp.service.IAiService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractAiProviderService extends ServiceImpl<AiMessageMapper, AiMessage> implements IAiService {

    protected static final String DEFAULT_SYSTEM_PROMPT =
            "你是探店笔记平台的AI助手，帮助用户推荐店铺、查询优惠券、解答美食问题。语气轻松友好，回答简洁，控制在50字以内。";
    protected static final String DEFAULT_AGENT_SYSTEM_PROMPT =
            "你是探店笔记平台的AI助手。用户询问店铺推荐、优惠券、评分、距离等信息时，"
                    + "必须优先调用工具查询真实业务数据，不要编造。"
                    + "如果提到优惠券，要先查店铺再查优惠券；"
                    + "如果提到评分、高分、最好评，要按评分从高到低返回；"
                    + "如果提到距离、附近、近、离我近，要按距离从近到远返回。";
    protected static final int MAX_REQUEST_MESSAGES = 12;
    protected static final String AGENT_PAYLOAD_PREFIX = "__AGENT_REPLY__";
    protected static final int MAX_HISTORY_MESSAGES = 10;

    @Resource
    protected IShopService shopService;

    @Resource
    protected IShopTypeService shopTypeService;

    @Resource
    protected IVoucherService voucherService;

    protected ObjectMapper objectMapper;
    protected String shopTypePrompt;

    @PostConstruct
    protected void initSupport() {
        this.objectMapper = new ObjectMapper();
        List<String> shopTypes = shopTypeService.list().stream()
                .map(shopType -> shopType.getName())
                .collect(Collectors.toList());
        this.shopTypePrompt = "平台支持的店铺类型包括：" + String.join("、", shopTypes)
                + "。当用户询问店铺推荐时，应根据需求匹配合适类型。";
    }

    @Override
    public Result getHistory() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        List<AiMessage> messages = lambdaQuery()
                .eq(AiMessage::getUserId, userId)
                .orderByDesc(AiMessage::getCreateTime)
                .last("limit " + MAX_HISTORY_MESSAGES)
                .list();
        Collections.reverse(messages);

        List<AiMessageDTO> result = messages.stream()
                .map(this::toHistoryMessage)
                .collect(Collectors.toList());
        return Result.ok(result);
    }

    protected Long getCurrentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }

    protected List<AiMessageDTO> normalizeRecentMessages(List<AiMessageDTO> messages) {
        List<AiMessageDTO> normalized = new ArrayList<>();
        if (messages == null) {
            return normalized;
        }

        for (AiMessageDTO msg : messages) {
            if (msg == null || msg.getContent() == null) {
                continue;
            }
            String content = msg.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            AiMessageDTO dto = new AiMessageDTO();
            dto.setRole("assistant".equals(msg.getRole()) ? "assistant" : "user");
            dto.setContent(content);
            dto.setShops(msg.getShops());
            normalized.add(dto);
        }

        if (normalized.size() <= MAX_REQUEST_MESSAGES) {
            return normalized;
        }
        return new ArrayList<>(normalized.subList(normalized.size() - MAX_REQUEST_MESSAGES, normalized.size()));
    }

    protected List<AiMessageDTO> historyWithoutLastUser(List<AiMessageDTO> recentMessages) {
        if (recentMessages == null || recentMessages.size() <= 1) {
            return new ArrayList<>();
        }
        return new ArrayList<>(recentMessages.subList(0, recentMessages.size() - 1));
    }

    protected AiMessageDTO getLastMessage(List<AiMessageDTO> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return null;
        }
        return recentMessages.get(recentMessages.size() - 1);
    }

    protected void saveLastUserMessage(Long userId, List<AiMessageDTO> recentMessages) {
        AiMessageDTO lastUserMsg = getLastMessage(recentMessages);
        if (lastUserMsg == null || !"user".equals(lastUserMsg.getRole())) {
            return;
        }
        save(new AiMessage()
                .setUserId(userId)
                .setRole("user")
                .setContent(lastUserMsg.getContent())
                .setCreateTime(LocalDateTime.now()));
    }

    protected void saveAssistantMessage(Long userId, String content) {
        saveAssistantMessage(userId, content, null);
    }

    protected void saveAssistantMessage(Long userId, String content, List<AgentReply.ShopCard> shops) {
        save(new AiMessage()
                .setUserId(userId)
                .setRole("assistant")
                .setContent(encodeAssistantContent(content, shops))
                .setCreateTime(LocalDateTime.now()));
    }

    protected AiMessageDTO toHistoryMessage(AiMessage msg) {
        AiMessageDTO dto = new AiMessageDTO();
        dto.setRole(msg.getRole());
        dto.setContent(msg.getContent());
        if (!"assistant".equals(msg.getRole()) || msg.getContent() == null || !msg.getContent().startsWith(AGENT_PAYLOAD_PREFIX)) {
            return dto;
        }

        try {
            String payloadJson = msg.getContent().substring(AGENT_PAYLOAD_PREFIX.length());
            AgentReply payload = objectMapper.readValue(payloadJson, AgentReply.class);
            dto.setContent(payload.getText());
            dto.setShops(payload.getShops());
        } catch (Exception ignored) {
        }
        return dto;
    }

    protected String encodeAssistantContent(String content, List<AgentReply.ShopCard> shops) {
        if (shops == null || shops.isEmpty()) {
            return content;
        }
        try {
            AgentReply payload = new AgentReply();
            payload.setText(content);
            payload.setShops(shops);
            return AGENT_PAYLOAD_PREFIX + objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return content;
        }
    }

    protected String buildAgentSystemPrompt(AgentIntent intent) {
        return DEFAULT_AGENT_SYSTEM_PROMPT + shopTypePrompt + buildIntentPrompt(intent);
    }

    protected AgentIntent analyzeIntent(List<AiMessageDTO> recentMessages, AiAgentRequest request) {
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        String lastUserText = lastMessage == null || lastMessage.getContent() == null ? "" : lastMessage.getContent();
        boolean needVoucher = lastUserText.contains("优惠") || lastUserText.contains("券");
        boolean sortByScore = lastUserText.contains("评分") || lastUserText.contains("高分") || lastUserText.contains("最好评");
        boolean sortByDistance = lastUserText.contains("距离") || lastUserText.contains("附近")
                || lastUserText.contains("近") || lastUserText.contains("离我");
        Double x = request == null ? null : request.getX();
        Double y = request == null ? null : request.getY();
        return new AgentIntent(needVoucher, sortByScore, sortByDistance, x, y);
    }

    protected String buildIntentPrompt(AgentIntent intent) {
        StringBuilder sb = new StringBuilder();
        if (intent.needVoucher) {
            sb.append("本轮用户关注优惠券，找到店铺后要补充优惠券信息。");
        }
        if (intent.sortByScore) {
            sb.append("本轮用户关注评分，搜索时按评分从高到低排序。");
        }
        if (intent.sortByDistance) {
            if (intent.x != null && intent.y != null) {
                sb.append("本轮用户关注距离，搜索时按距离从近到远排序。");
            } else {
                sb.append("本轮用户关注距离，但当前没有定位，回答时要说明距离能力受限。");
            }
        }
        return sb.toString();
    }

    protected String resolveSortBy(String sortBy, AgentIntent intent) {
        if (sortBy != null && !sortBy.trim().isEmpty()) {
            return sortBy.trim();
        }
        if (intent.sortByDistance && intent.x != null && intent.y != null) {
            return "distance_asc";
        }
        if (intent.sortByScore) {
            return "score_desc";
        }
        return "default";
    }

    protected void applyShopSorting(List<Shop> shops, String sortBy, Double x, Double y) {
        if ("distance_asc".equals(sortBy) && x != null && y != null) {
            for (Shop shop : shops) {
                if (shop.getX() != null && shop.getY() != null) {
                    shop.setDistance(calculateDistanceMeters(x, y, shop.getX(), shop.getY()));
                }
            }
            shops.sort(Comparator.comparing(item -> item.getDistance() == null ? Double.MAX_VALUE : item.getDistance()));
        } else if ("score_desc".equals(sortBy)) {
            shops.sort(Comparator.comparing((Shop item) -> item.getScore() == null ? Integer.MIN_VALUE : item.getScore()).reversed());
        }

        if (shops.size() > 5) {
            shops.subList(5, shops.size()).clear();
        }
    }

    protected double calculateDistanceMeters(Double x1, Double y1, Double x2, Double y2) {
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

    protected Map<String, Object> toShopMap(Shop shop) {
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

    protected List<Shop> searchShopsByKeywordOrType(String keyword, String sortBy, Double x, Double y) {
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

    protected List<Long> resolveMatchedShopTypeIds(String keyword) {
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
            if (normalizedKeyword.contains(normalizedTypeName) || normalizedTypeName.contains(normalizedKeyword)) {
                matchedTypeIds.add(shopType.getId());
            }
        }
        return new ArrayList<>(matchedTypeIds);
    }

    protected String normalizeTypeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("·", "")
                .replace(".", "")
                .replace("，", "")
                .replace(",", "")
                .replace("、", "")
                .replace(" ", "")
                .trim();
    }

    protected Map<String, Object> toVoucherMap(Voucher voucher) {
        Map<String, Object> result = new HashMap<>();
        result.put("shopId", voucher.getShopId());
        result.put("title", voucher.getTitle());
        result.put("subTitle", voucher.getSubTitle());
        return result;
    }

    protected void mergeShopCard(Map<Long, AgentReply.ShopCard> collectedShopMap, Map<String, Object> shopMap) {
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

    protected void mergeVoucherCards(Map<Long, AgentReply.ShopCard> collectedShopMap, List<Map<String, Object>> voucherList) {
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

    protected void enrichShopCardsWithVouchers(List<AgentReply.ShopCard> shops) {
        for (AgentReply.ShopCard shop : shops) {
            if (shop.getId() == null || shop.getVoucherTitle() != null) {
                continue;
            }
            Result voucherResult = voucherService.queryVoucherOfShop(shop.getId());
            Object data = voucherResult.getData();
            if (!(data instanceof List) || ((List<?>) data).isEmpty()) {
                continue;
            }
            Object first = ((List<?>) data).get(0);
            if (!(first instanceof Voucher)) {
                continue;
            }
            Voucher voucher = (Voucher) first;
            shop.setVoucherTitle(voucher.getTitle());
            shop.setVoucherDesc(voucher.getSubTitle());
        }
    }

    protected String safeAssistantReply(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "我已经查到一些结果，但总结为空，你可以换一种问法继续问我。";
        }
        return content.trim();
    }

    protected Result validateAndPrepareUserRequest(Long userId, List<AiMessageDTO> recentMessages) {
        if (userId == null) {
            return Result.fail("用户未登录");
        }
        if (recentMessages == null || recentMessages.isEmpty()) {
            return Result.fail("消息内容不能为空");
        }
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        if (lastMessage == null || lastMessage.getContent() == null || lastMessage.getContent().trim().isEmpty()) {
            return Result.fail("消息内容不能为空");
        }
        return null;
    }

    protected static class AgentIntent {
        protected final boolean needVoucher;
        protected final boolean sortByScore;
        protected final boolean sortByDistance;
        protected final Double x;
        protected final Double y;

        protected AgentIntent(boolean needVoucher, boolean sortByScore, boolean sortByDistance, Double x, Double y) {
            this.needVoucher = needVoucher;
            this.sortByScore = sortByScore;
            this.sortByDistance = sortByDistance;
            this.x = x;
            this.y = y;
        }
    }
}
