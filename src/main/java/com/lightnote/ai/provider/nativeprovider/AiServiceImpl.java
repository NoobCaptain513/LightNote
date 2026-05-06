package com.lightnote.ai.provider.nativeprovider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightnote.ai.model.AgentIntent;
import com.lightnote.ai.provider.AbstractAiProviderService;
import com.lightnote.ai.tool.ToolResultCollector;
import com.lightnote.dto.AiAgentRequest;
import com.lightnote.dto.AiChatRequest;
import com.lightnote.dto.AiMessageDTO;
import com.lightnote.dto.AgentReply;
import com.lightnote.dto.Result;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service("aiService")
@ConditionalOnProperty(prefix = "ai.provider", name = "type", havingValue = "native", matchIfMissing = true)
public class AiServiceImpl extends AbstractAiProviderService {

    @Resource
    private NativeAiClient nativeAiClient;

    @Resource
    private NativeToolSchemaFactory nativeToolSchemaFactory;

    @Resource
    private ToolResultCollector toolResultCollector;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public Result chat(AiChatRequest request) {
        long startTime = System.currentTimeMillis();
        Long userId = getCurrentUserId();
        List<AiMessageDTO> recentMessages = normalizeRecentMessages(request == null ? null : request.getMessages());
        Result validateResult = validateUserRequest(userId, recentMessages);
        if (validateResult != null) {
            return validateResult;
        }

        saveLastUserMessage(userId, recentMessages);
        String systemPrompt = buildChatSystemPrompt(recentMessages);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);

        try {
            String responseBody = nativeAiClient.callModel(
                    nativeAiClient.buildChatBody(
                            nativeAiClient.buildRequestMessages(systemPrompt, recentMessages),
                            null
                    )
            );
            String aiContent = safeAssistantReply(nativeAiClient.extractAssistantContent(responseBody));
            saveAssistantMessage(userId, aiContent);
            recordUsage(userId, "chat", promptTrace, aiContent, startTime, true, null);
            return Result.ok(aiContent);
        } catch (IOException e) {
            e.printStackTrace();
            recordUsage(userId, "chat", promptTrace, e.getMessage(), startTime, false, e.getMessage());
            return Result.fail("AI服务暂时不可用: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            recordUsage(userId, "chat", promptTrace, e.getMessage(), startTime, false, e.getMessage());
            return Result.fail("AI服务处理异常: " + e.getMessage());
        }
    }

    @Override
    public Result agentChat(AiAgentRequest request) {
        long startTime = System.currentTimeMillis();
        Long userId = getCurrentUserId();
        List<AiMessageDTO> recentMessages = normalizeRecentMessages(request == null ? null : request.getMessages());
        Result validateResult = validateUserRequest(userId, recentMessages);
        if (validateResult != null) {
            return validateResult;
        }

        saveLastUserMessage(userId, recentMessages);

        AgentIntent intent = analyzeIntent(recentMessages, request);
        String systemPrompt = buildAgentSystemPrompt(intent, recentMessages);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);

        try {
            Map<Long, AgentReply.ShopCard> collectedShopMap = new LinkedHashMap<>();
            List<Map<String, Object>> messages = nativeAiClient.buildRequestMessages(systemPrompt, recentMessages);
            List<Map<String, Object>> tools = nativeToolSchemaFactory.buildSchemas();

            for (int i = 0; i < 5; i++) {
                String responseBody = nativeAiClient.callModel(nativeAiClient.buildChatBody(messages, tools));
                Map<String, Object> result = nativeAiClient.parseResponse(responseBody);
                Map<String, Object> output = nativeAiClient.castMap(result.get("output"));
                if (output == null) {
                    recordUsage(userId, "agent", promptTrace, "AI响应异常", startTime, false, "AI响应异常");
                    return Result.fail("AI响应异常");
                }

                List<Map<String, Object>> choices = nativeAiClient.castListOfMap(output.get("choices"));
                if (choices == null || choices.isEmpty()) {
                    recordUsage(userId, "agent", promptTrace, "AI返回为空", startTime, false, "AI返回为空");
                    return Result.fail("AI返回为空");
                }

                Map<String, Object> choice = choices.get(0);
                String finishReason = choice.get("finish_reason") == null ? null : String.valueOf(choice.get("finish_reason"));
                Map<String, Object> assistantMessage = nativeAiClient.castMap(choice.get("message"));
                if (assistantMessage == null) {
                    recordUsage(userId, "agent", promptTrace, "AI返回格式异常", startTime, false, "AI返回格式异常");
                    return Result.fail("AI返回格式异常");
                }
                messages.add(assistantMessage);

                if ("stop".equals(finishReason)) {
                    String finalText = safeAssistantReply(assistantMessage.get("content") == null
                            ? null
                            : String.valueOf(assistantMessage.get("content")));
                    List<AgentReply.ShopCard> replyShops = shopCardAssembler.toShopCardList(collectedShopMap);
                    if (intent.isNeedVoucher()) {
                        shopAgentToolService.enrichShopCardsWithVouchers(replyShops);
                    }
                    saveAssistantMessage(userId, finalText, replyShops);
                    recordUsage(userId, "agent", promptTrace, finalText, startTime, true, null);
                    return Result.ok(shopCardAssembler.buildReply(finalText, replyShops));
                }

                if (!"tool_calls".equals(finishReason)) {
                    recordUsage(userId, "agent", promptTrace, "AI返回格式异常", startTime, false, "AI返回格式异常");
                    return Result.fail("AI返回格式异常");
                }

                List<Map<String, Object>> toolCalls = nativeAiClient.castListOfMap(assistantMessage.get("tool_calls"));
                if (toolCalls == null || toolCalls.isEmpty()) {
                    recordUsage(userId, "agent", promptTrace, "AI工具调用为空", startTime, false, "AI工具调用为空");
                    return Result.fail("AI工具调用为空");
                }

                for (Map<String, Object> toolCall : toolCalls) {
                    String toolCallId = toolCall.get("id") == null ? null : String.valueOf(toolCall.get("id"));
                    Map<String, Object> function = nativeAiClient.castMap(toolCall.get("function"));
                    if (function == null) {
                        continue;
                    }
                    String toolName = function.get("name") == null ? null : String.valueOf(function.get("name"));
                    String arguments = function.get("arguments") == null ? "{}" : String.valueOf(function.get("arguments"));
                    String toolResult = executeTool(toolName, arguments, intent);
                    toolResultCollector.collect(toolName, toolResult, collectedShopMap);
                    messages.add(buildToolResultMessage(toolCallId, toolName, toolResult));
                }
            }

            recordUsage(userId, "agent", promptTrace, "Agent处理超时", startTime, false, "Agent处理超时");
            return Result.fail("Agent处理超时，请简化你的问题");
        } catch (IOException e) {
            e.printStackTrace();
            recordUsage(userId, "agent", promptTrace, e.getMessage(), startTime, false, e.getMessage());
            return Result.fail("AI服务暂时不可用: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            recordUsage(userId, "agent", promptTrace, e.getMessage(), startTime, false, e.getMessage());
            return Result.fail("Agent异常: " + e.getMessage());
        }
    }

    private String executeTool(String toolName, String inputJson, AgentIntent intent) {
        try {
            Map<String, Object> input = objectMapper.readValue(inputJson, Map.class);

            if ("searchShop".equals(toolName)) {
                String keyword = input.get("keyword") == null ? "" : String.valueOf(input.get("keyword")).trim();
                if (keyword.isEmpty()) {
                    return "[]";
                }
                String sortBy = resolveSortBy(valueAsString(input.get("sortBy")), intent);
                Double x = readDouble(input.get("x"), intent.getX());
                Double y = readDouble(input.get("y"), intent.getY());
                return objectMapper.writeValueAsString(shopAgentToolService.searchShop(keyword, sortBy, x, y));
            }

            if ("getVoucher".equals(toolName)) {
                Long shopId = readLong(input.get("shopId"));
                if (shopId == null) {
                    return errorJson("缺少店铺ID");
                }
                return objectMapper.writeValueAsString(shopAgentToolService.getVoucher(shopId));
            }

            if ("getShopDetail".equals(toolName)) {
                Long shopId = readLong(input.get("shopId"));
                if (shopId == null) {
                    return errorJson("缺少店铺ID");
                }
                return objectMapper.writeValueAsString(shopAgentToolService.getShopDetail(shopId));
            }
        } catch (Exception e) {
            return errorJson("工具执行失败: " + e.getMessage());
        }
        return errorJson("未知工具");
    }

    private Map<String, Object> buildToolResultMessage(String toolCallId, String toolName, String toolResult) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("name", toolName);
        message.put("content", toolResult);
        return message;
    }

    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception ignored) {
            return "{\"error\":\"" + message + "\"}";
        }
    }

    private String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double readDouble(Object value, Double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private Long readLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
