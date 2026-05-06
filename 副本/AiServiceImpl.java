package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiChatRequest;
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
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service("aiService")
@ConditionalOnProperty(prefix = "ai.provider", name = "type", havingValue = "native", matchIfMissing = true)
public class AiServiceImpl extends ServiceImpl<AiMessageMapper, AiMessage> implements IAiService {

    @Value("${ai.api-key}")
    private String apiKey;

    @Value("${ai.model}")
    private String model;

    @Value("${ai.api-url}")
    private String defaultApiUrl;

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private IVoucherService voucherService;

    private OkHttpClient client;
    private ObjectMapper objectMapper;
    private String shopTypePrompt;

    @PostConstruct
    public void init() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        objectMapper = new ObjectMapper();

        List<String> shopTypes = shopTypeService.list().stream()
                .map(shopType -> shopType.getName())
                .collect(Collectors.toList());
        shopTypePrompt = "平台支持的店铺类型包括：" + String.join("、", shopTypes) + "。当用户询问店铺推荐时，应根据用户需求匹配相应的类型，并调用searchShop工具查询。";
    }

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是探店笔记平台的AI助手，帮用户推荐店铺、查询优惠券、解答美食问题。语气轻松友好，回答简洁，控制在150字以内。";
    private static final String DEFAULT_AGENT_SYSTEM_PROMPT =
            "你是探店笔记平台的AI助手。当用户询问店铺推荐、优惠券、评分、距离等信息时，"
                    + "必须优先调用工具查询真实数据，不要凭空编造。"
                    + "如果用户提到优惠券，先找到店铺再查询优惠券；"
                    + "如果用户提到评分高、评分最好，就按评分从高到低返回；"
                    + "如果用户提到距离近、附近、离我近，就按距离从近到远返回。"
                    + "回答保持简洁友好，控制在150字以内。";
    private static final int MAX_REQUEST_MESSAGES = 12;
    private static final String AGENT_PAYLOAD_PREFIX = "__AGENT_REPLY__";
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private static class AgentIntent {
        private final boolean needVoucher;
        private final boolean sortByScore;
        private final boolean sortByDistance;
        private final Double x;
        private final Double y;

        private AgentIntent(boolean needVoucher, boolean sortByScore, boolean sortByDistance, Double x, Double y) {
            this.needVoucher = needVoucher;
            this.sortByScore = sortByScore;
            this.sortByDistance = sortByDistance;
            this.x = x;
            this.y = y;
        }
    }

    @Override
    public Result chat(AiChatRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        List<AiMessageDTO> recentMessages = normalizeRecentMessages(request == null ? null : request.getMessages());
        if (recentMessages.isEmpty()) {
            return Result.fail("消息内容不能为空");
        }

        saveLastUserMessage(userId, recentMessages);

        try {
            // 调用模型
            String responseBody = callModel(buildChatBody(buildRequestMessages(DEFAULT_SYSTEM_PROMPT, recentMessages), null));
            String aiContent = extractAssistantContent(responseBody);
            if (aiContent == null || aiContent.trim().isEmpty()) {
                return Result.fail("AI服务响应为空");
            }
            saveAssistantMessage(userId, aiContent);
            return Result.ok(aiContent);
        } catch (IOException e) {
            e.printStackTrace();
            return Result.fail("AI服务暂时不可用: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("AI服务处理异常: " + e.getMessage());
        }
    }
    private Long getCurrentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
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

    @Override
    public Result agentChat(AiAgentRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        List<AiMessageDTO> recentMessages = normalizeRecentMessages(request == null ? null : request.getMessages());
        if (recentMessages.isEmpty()) {
            return Result.fail("消息内容不能为空");
        }

        saveLastUserMessage(userId, recentMessages);

        try {
            AgentIntent intent = analyzeIntent(recentMessages, request);
            Map<Long, AgentReply.ShopCard> collectedShopMap = new LinkedHashMap<>();
            List<Map<String, Object>> messages = buildRequestMessages(
                    DEFAULT_AGENT_SYSTEM_PROMPT + shopTypePrompt + buildIntentPrompt(intent),
                    recentMessages
            );
            List<Map<String, Object>> tools = buildTools();

            for (int i = 0; i < 5; i++) {
                String responseBody = callModel(buildChatBody(messages, tools));
                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                Map<String, Object> output = castMap(result.get("output"));
                if (output == null) {
                    return Result.fail("AI响应异常");
                }

                List<Map<String, Object>> choices = castListOfMap(output.get("choices"));
                if (choices == null || choices.isEmpty()) {
                    return Result.fail("AI返回为空");
                }

                Map<String, Object> choice = choices.get(0);
                String finishReason = choice.get("finish_reason") == null ? null : String.valueOf(choice.get("finish_reason"));
                Map<String, Object> assistantMsg = castMap(choice.get("message"));
                if (assistantMsg == null) {
                    return Result.fail("AI返回格式异常");
                }
                messages.add(assistantMsg);

                if ("stop".equals(finishReason)) {
                    String finalAiText = assistantMsg.get("content") == null ? "" : String.valueOf(assistantMsg.get("content"));
                    if (finalAiText.trim().isEmpty()) {
                        finalAiText = "我已经查到一些结果，但当前总结为空，你可以换一种问法继续问我。";
                    }
                    List<AgentReply.ShopCard> replyShops = new ArrayList<>(collectedShopMap.values());
                    if (intent.needVoucher) {
                        enrichShopCardsWithVouchers(replyShops);
                    }
                    saveAssistantMessage(userId, finalAiText, replyShops);

                    AgentReply reply = new AgentReply();
                    reply.setText(finalAiText);
                    reply.setShops(replyShops);
                    return Result.ok(reply);
                }

                if (!"tool_calls".equals(finishReason)) {
                    return Result.fail("AI返回格式异常");
                }

                List<Map<String, Object>> toolCalls = castListOfMap(assistantMsg.get("tool_calls"));
                if (toolCalls == null || toolCalls.isEmpty()) {
                    return Result.fail("AI工具调用为空");
                }

                for (Map<String, Object> toolCall : toolCalls) {
                    String toolCallId = toolCall.get("id") == null ? null : String.valueOf(toolCall.get("id"));
                    Map<String, Object> function = castMap(toolCall.get("function"));
                    if (function == null) {
                        continue;
                    }
                    String toolName = function.get("name") == null ? null : String.valueOf(function.get("name"));
                    String arguments = function.get("arguments") == null ? "{}" : String.valueOf(function.get("arguments"));
                    String toolResult = executeTool(toolName, arguments, intent);
                    collectShopCards(toolName, toolResult, collectedShopMap);

                    Map<String, Object> toolResultMsg = new HashMap<>();
                    toolResultMsg.put("role", "tool");
                    toolResultMsg.put("tool_call_id", toolCallId);
                    toolResultMsg.put("name", toolName);
                    toolResultMsg.put("content", toolResult);
                    messages.add(toolResultMsg);
                }
            }

            return Result.fail("Agent处理超时，请简化你的问题");
        } catch (IOException e) {
            e.printStackTrace();
            return Result.fail("AI服务暂时不可用: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("Agent异常: " + e.getMessage());
        }
    }



    private void saveLastUserMessage(Long userId, List<AiMessageDTO> recentMessages) {
        AiMessageDTO lastUserMsg = recentMessages.get(recentMessages.size() - 1);
        if (!"user".equals(lastUserMsg.getRole())) {
            return;
        }
        save(new AiMessage()
                .setUserId(userId)
                .setRole("user")
                .setContent(lastUserMsg.getContent())
                .setCreateTime(LocalDateTime.now()));
    }

    private void saveAssistantMessage(Long userId, String content) {
        saveAssistantMessage(userId, content, null);
    }

    private void saveAssistantMessage(Long userId, String content, List<AgentReply.ShopCard> shops) {
        save(new AiMessage()
                .setUserId(userId)
                .setRole("assistant")
                .setContent(encodeAssistantContent(content, shops))
                .setCreateTime(LocalDateTime.now()));
    }

    private AiMessageDTO toHistoryMessage(AiMessage msg) {
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

    private String encodeAssistantContent(String content, List<AgentReply.ShopCard> shops) {
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
    private List<AiMessageDTO> normalizeRecentMessages(List<AiMessageDTO> messages) {
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
            String role = "assistant".equals(msg.getRole()) ? "assistant" : "user";
            AiMessageDTO dto = new AiMessageDTO();
            dto.setRole(role);
            dto.setContent(content);
            normalized.add(dto);
        }

        if (normalized.size() <= MAX_REQUEST_MESSAGES) {
            return normalized;
        }
        return new ArrayList<>(normalized.subList(normalized.size() - MAX_REQUEST_MESSAGES, normalized.size()));
    }

    private List<Map<String, Object>> buildRequestMessages(String systemPrompt, List<AiMessageDTO> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(buildMessage("system", systemPrompt));
        for (AiMessageDTO msg : history) {
            messages.add(buildMessage(msg.getRole(), msg.getContent()));
        }
        return messages;
    }

    private Map<String, Object> buildChatBody(List<Map<String, Object>> messages,
                                              List<Map<String, Object>> tools) {
        Map<String, Object> input = new HashMap<>();
        input.put("messages", messages);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("max_tokens", 1000);
        parameters.put("result_format", "message");
        if (tools != null && !tools.isEmpty()) {
            parameters.put("tools", tools);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);
        return body;
    }

    private String callModel(Map<String, Object> body) throws IOException {
        String json = objectMapper.writeValueAsString(body);
        Request httpRequest = new Request.Builder()
                .url(defaultApiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("AI服务请求失败[" + response.code() + "]: " + responseBody);
            }
            return responseBody;
        }
    }

    private String extractAssistantContent(String responseBody) throws IOException {
        Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> output = castMap(result.get("output"));
        if (output == null) {
            return null;
        }

        List<Map<String, Object>> choices = castListOfMap(output.get("choices"));
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> message = castMap(choices.get(0).get("message"));
            if (message != null && message.get("content") != null) {
                return String.valueOf(message.get("content"));
            }
        }

        Object text = output.get("text");
        return text == null ? null : text.toString();
    }

    private Map<String, Object> buildMessage(String role, String content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        Map<String, Object> searchShop = new HashMap<>();
        searchShop.put("type", "function");
        searchShop.put("function", Map.of(
                "name", "searchShop",
                "description", "根据关键词搜索店铺，支持按评分从高到低或按距离从近到远排序。当用户提到评分、距离、近、优惠券时，需要结合这些需求查询。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "keyword", Map.of("type", "string", "description", "搜索关键词，例如具体的菜品（如麻辣烫、小龙虾）、餐厅名称（如海底捞、星巴克）、或食物类型（如火锅、烧烤、中餐、西餐）。即使是泛化的美食需求，也应尝试提取关键词进行搜索。"),
                                "sortBy", Map.of("type", "string", "description", "排序方式，可选值：default、score_desc、distance_asc"),
                                "x", Map.of("type", "number", "description", "用户经度，按距离排序时传入"),
                                "y", Map.of("type", "number", "description", "用户纬度，按距离排序时传入")
                        ),
                        "required", List.of("keyword")
                )
        ));
        tools.add(searchShop);

        Map<String, Object> getVoucher = new HashMap<>();
        getVoucher.put("type", "function");
        getVoucher.put("function", Map.of(
                "name", "getVoucher",
                "description", "查询指定店铺的优惠券，返回优惠券标题和折扣信息",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "shopId", Map.of("type", "integer", "description", "店铺ID")
                        ),
                        "required", List.of("shopId")
                )
        ));
        tools.add(getVoucher);

        Map<String, Object> getShopDetail = new HashMap<>();
        getShopDetail.put("type", "function");
        getShopDetail.put("function", Map.of(
                "name", "getShopDetail",
                "description", "查询店铺详细信息，包括评分、地址、营业时间、均价",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "shopId", Map.of("type", "integer", "description", "店铺ID")
                        ),
                        "required", List.of("shopId")
                )
        ));
        tools.add(getShopDetail);

        return tools;
    }

    private String executeTool(String toolName, String inputJson, AgentIntent intent) {
        try {
            Map<String, Object> input = objectMapper.readValue(inputJson, Map.class);

            if ("searchShop".equals(toolName)) {
                String keyword = input.get("keyword") == null ? "" : String.valueOf(input.get("keyword")).trim();
                if (keyword.isEmpty()) {
                    return "[]";
                }
                String sortBy = resolveSortBy(input.get("sortBy"), intent);
                Double x = readDouble(input.get("x"), intent.x);
                Double y = readDouble(input.get("y"), intent.y);
                List<Shop> shops = searchShopsByKeywordOrType(keyword, sortBy, x, y);
                List<Map<String, Object>> result = shops.stream().map(this::toShopMap).collect(Collectors.toList());
                return objectMapper.writeValueAsString(result);
            }

            if ("getVoucher".equals(toolName)) {
                Number shopId = (Number) input.get("shopId");
                if (shopId == null) {
                    return "{\"error\":\"缺少店铺ID\"}";
                }
                Result voucherResult = voucherService.queryVoucherOfShop(shopId.longValue());
                return objectMapper.writeValueAsString(voucherResult.getData());
            }

            if ("getShopDetail".equals(toolName)) {
                Number shopId = (Number) input.get("shopId");
                if (shopId == null) {
                    return "{\"error\":\"缺少店铺ID\"}";
                }
                Shop shop = shopService.getById(shopId.longValue());
                if (shop == null) {
                    return "{\"error\":\"店铺不存在\"}";
                }
                return objectMapper.writeValueAsString(toShopMap(shop));
            }
        } catch (Exception e) {
            return "{\"error\":\"工具执行失败: " + e.getMessage() + "\"}";
        }
        return "{\"error\":\"未知工具\"}";
    }

    private Map<String, Object> toShopMap(Shop shop) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", shop.getId());
        m.put("name", shop.getName());
        m.put("area", shop.getArea());
        m.put("address", shop.getAddress());
        m.put("score", shop.getScore() != null ? shop.getScore() / 10.0 : null);
        m.put("avgPrice", shop.getAvgPrice());
        m.put("distance", shop.getDistance() != null ? String.format("%.0fm", shop.getDistance()) : null);
        m.put("openHours", shop.getOpenHours());
        return m;
    }

    private void collectShopCards(String toolName,
                                  String toolResult,
                                  Map<Long, AgentReply.ShopCard> collectedShopMap) {
        try {
            if ("searchShop".equals(toolName)) {
                List<Map<String, Object>> shopList = objectMapper.readValue(toolResult, List.class);
                for (Map<String, Object> shopMap : shopList) {
                    mergeShopCard(collectedShopMap, shopMap);
                }
            }

            if ("getShopDetail".equals(toolName)) {
                Map<String, Object> shopMap = objectMapper.readValue(toolResult, Map.class);
                mergeShopCard(collectedShopMap, shopMap);
            }

            if ("getVoucher".equals(toolName)) {
                List<Map<String, Object>> voucherList = objectMapper.readValue(toolResult, List.class);
                mergeVoucherCards(collectedShopMap, voucherList);
            }
        } catch (Exception ignored) {
        }
    }

    private void mergeShopCard(Map<Long, AgentReply.ShopCard> collectedShopMap, Map<String, Object> shopMap) {
        if (shopMap == null || shopMap.get("id") == null) {
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

    private AgentIntent analyzeIntent(List<AiMessageDTO> recentMessages, AiAgentRequest request) {
        String lastUserText = recentMessages.get(recentMessages.size() - 1).getContent();
        boolean needVoucher = lastUserText.contains("优惠") || lastUserText.contains("券");
        boolean sortByScore = lastUserText.contains("评分") || lastUserText.contains("高分") || lastUserText.contains("最好评");
        boolean sortByDistance = lastUserText.contains("距离") || lastUserText.contains("附近") || lastUserText.contains("近") || lastUserText.contains("离我");
        Double x = request == null ? null : request.getX();
        Double y = request == null ? null : request.getY();
        return new AgentIntent(needVoucher, sortByScore, sortByDistance, x, y);
    }

    private String buildIntentPrompt(AgentIntent intent) {
        StringBuilder sb = new StringBuilder();
        if (intent.needVoucher) {
            sb.append("本轮用户明确关注优惠券，必须在找到店铺后调用getVoucher工具查询至少一家店铺的优惠券。");
        }
        if (intent.sortByScore) {
            sb.append("本轮用户明确要求看评分，搜索店铺时按评分从高到低排序。");
        }
        if (intent.sortByDistance) {
            if (intent.x != null && intent.y != null) {
                sb.append("本轮用户明确要求看距离，搜索店铺时按距离从近到远排序。");
            } else {
                sb.append("本轮用户明确要求看距离，但当前没有用户定位，请说明距离排序受限。");
            }
        }
        return sb.toString();
    }

    private String resolveSortBy(Object sortBy, AgentIntent intent) {
        if (sortBy != null) {
            String sortValue = String.valueOf(sortBy).trim();
            if (!sortValue.isEmpty()) {
                return sortValue;
            }
        }
        if (intent.sortByDistance && intent.x != null && intent.y != null) {
            return "distance_asc";
        }
        if (intent.sortByScore) {
            return "score_desc";
        }
        return "default";
    }

    private Double readDouble(Object value, Double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return fallback;
    }

    private void applyShopSorting(List<Shop> shops, String sortBy, Double x, Double y) {
        if ("distance_asc".equals(sortBy) && x != null && y != null) {
            for (Shop shop : shops) {
                if (shop.getX() != null && shop.getY() != null) {
                    shop.setDistance(calculateDistanceMeters(x, y, shop.getX(), shop.getY()));
                }
            }
            shops.sort(Comparator.comparing(
                    item -> item.getDistance() == null ? Double.MAX_VALUE : item.getDistance()
            ));
        } else if ("score_desc".equals(sortBy)) {
            shops.sort(Comparator.comparing(
                    (Shop item) -> item.getScore() == null ? Integer.MIN_VALUE : item.getScore()
            ).reversed());
        }

        if (shops.size() > 5) {
            shops.subList(5, shops.size()).clear();
        }
    }

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
            if (normalizedKeyword.contains(normalizedTypeName) || normalizedTypeName.contains(normalizedKeyword)) {
                matchedTypeIds.add(shopType.getId());
            }
        }
        return new ArrayList<>(matchedTypeIds);
    }

    private String normalizeTypeText(String text) {
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

    private void enrichShopCardsWithVouchers(List<AgentReply.ShopCard> shops) {
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

    private void mergeVoucherCards(Map<Long, AgentReply.ShopCard> collectedShopMap, List<Map<String, Object>> voucherList) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castListOfMap(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : null;
    }
}
