package com.lightnote.ai.provider.langchain4j;

import com.lightnote.ai.model.AgentIntent;
import com.lightnote.ai.provider.AbstractAiProviderService;
import com.lightnote.dto.AiAgentRequest;
import com.lightnote.dto.AiChatRequest;
import com.lightnote.dto.AiMessageDTO;
import com.lightnote.dto.AgentReply;
import com.lightnote.dto.Result;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service("langChain4jService")
@ConditionalOnProperty(prefix = "ai.provider", name = "type", havingValue = "langchain4j")
public class AiLangChain4jServiceImpl extends AbstractAiProviderService {

    @Value("${ai.api-key}")
    private String apiKey;

    @Value("${ai.model}")
    private String model;

    @Value("${ai.compatible-base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String compatibleBaseUrl;

    private ChatModel chatModel;
    private LangChainAssistant chatAssistant;
    private StreamingLangChainAssistant streamingChatAssistant;
    private OpenAiStreamingChatModel streamingChatModel;
    private final ConcurrentMap<Object, ChatMemory> chatMemories = new ConcurrentHashMap<>();

    @PostConstruct
    public void initLangChain4j() {
        String baseUrl = compatibleBaseUrl.endsWith("/v1") ? compatibleBaseUrl : compatibleBaseUrl + "/v1";
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .timeout(Duration.ofSeconds(60))
                .build();
        this.streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .timeout(Duration.ofSeconds(60))
                .build();

        this.chatAssistant = AiServices.builder(LangChainAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(this::getOrCreateMemory)
                .build();
        this.streamingChatAssistant = AiServices.builder(StreamingLangChainAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(this::getOrCreateMemory)
                .build();
    }

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
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        String systemPrompt = buildChatSystemPrompt(recentMessages);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        String memoryId = seedHistoryMemory("lc4j-chat", historyWithoutLastUser(recentMessages));

        try {
            String content = chatAssistant.chat(memoryId, systemPrompt, lastMessage.getContent());
            String finalText = safeAssistantReply(content);
            saveAssistantMessage(userId, finalText);
            recordUsage(userId, "chat", promptTrace, finalText, startTime, true, null);
            return Result.ok(finalText);
        } catch (Exception e) {
            e.printStackTrace();
            recordUsage(userId, "chat", promptTrace, e.getMessage(), startTime, false, e.getMessage());
            return Result.fail("LangChain4j 聊天失败: " + e.getMessage());
        } finally {
            chatMemories.remove(memoryId);
        }
    }

    @Override
    public SseEmitter streamChat(AiChatRequest request) {
        Long userId = getCurrentUserId();
        List<AiMessageDTO> recentMessages = normalizeRecentMessages(request == null ? null : request.getMessages());
        Result validateResult = validateUserRequest(userId, recentMessages);
        if (validateResult != null) {
            return aiStreamService.errorEmitter(validateResult.getErrorMsg());
        }

        saveLastUserMessage(userId, recentMessages);
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        String systemPrompt = buildChatSystemPrompt(recentMessages);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        String memoryId = seedHistoryMemory("lc4j-chat-stream", historyWithoutLastUser(recentMessages));
        long startTime = System.currentTimeMillis();

        return aiStreamService.stream(emitter -> {
            aiStreamService.sendStart(emitter);
            StringBuilder contentBuilder = new StringBuilder();
            TokenStream tokenStream = streamingChatAssistant.chat(memoryId, systemPrompt, lastMessage.getContent());
            tokenStream.onPartialResponse(delta -> {
                        if (delta == null || delta.isEmpty()) {
                            return;
                        }
                        contentBuilder.append(delta);
                        try {
                            aiStreamService.sendDelta(emitter, delta);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .onError(error -> {
                        recordUsage(userId, "chat", promptTrace, error.getMessage(), startTime, false, error.getMessage());
                        chatMemories.remove(memoryId);
                        try {
                            aiStreamService.sendError(emitter, error.getMessage());
                        } catch (Exception ignored) {
                        }
                        emitter.completeWithError(error);
                    })
                    .onCompleteResponse(response -> {
                        String finalText = safeAssistantReply(contentBuilder.toString());
                        saveAssistantMessage(userId, finalText);
                        recordUsage(userId, "chat", promptTrace, finalText, startTime, true, null);
                        chatMemories.remove(memoryId);
                        try {
                            aiStreamService.sendDone(emitter, finalText, finalText);
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    })
                    .start();
        });
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
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        AgentIntent intent = analyzeIntent(recentMessages, request);
        String systemPrompt = buildAgentSystemPrompt(intent, recentMessages);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        Map<Long, AgentReply.ShopCard> collectedShopMap = new LinkedHashMap<>();
        LangChainAssistant agentAssistant = AiServices.builder(LangChainAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(this::getOrCreateMemory)
                .tools(new LangChainShopTools(intent, collectedShopMap))
                .build();
        String memoryId = seedHistoryMemory("lc4j-agent", historyWithoutLastUser(recentMessages));

        try {
            String content = agentAssistant.chat(memoryId, systemPrompt, lastMessage.getContent());
            String finalText = safeAssistantReply(content);
            List<AgentReply.ShopCard> replyShops = shopCardAssembler.toShopCardList(collectedShopMap);
            if (intent.isNeedVoucher()) {
                shopAgentToolService.enrichShopCardsWithVouchers(replyShops);
            }

            saveAssistantMessage(userId, finalText, replyShops);
            recordUsage(userId, "agent", promptTrace, finalText, startTime, true, null);
            return Result.ok(shopCardAssembler.buildReply(finalText, replyShops));
        } catch (Exception e) {
            e.printStackTrace();
            recordUsage(userId, "agent", promptTrace, e.getMessage(), startTime, false, e.getMessage());
            return Result.fail("LangChain4j Agent 失败: " + e.getMessage());
        } finally {
            chatMemories.remove(memoryId);
        }
    }

    @Override
    public SseEmitter streamAgentChat(AiAgentRequest request) {
        Long userId = getCurrentUserId();
        List<AiMessageDTO> recentMessages = normalizeRecentMessages(request == null ? null : request.getMessages());
        Result validateResult = validateUserRequest(userId, recentMessages);
        if (validateResult != null) {
            return aiStreamService.errorEmitter(validateResult.getErrorMsg());
        }

        saveLastUserMessage(userId, recentMessages);
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        AgentIntent intent = analyzeIntent(recentMessages, request);
        String systemPrompt = buildAgentSystemPrompt(intent, recentMessages);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        Map<Long, AgentReply.ShopCard> collectedShopMap = new LinkedHashMap<>();
        StreamingLangChainAssistant agentAssistant = AiServices.builder(StreamingLangChainAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(this::getOrCreateMemory)
                .tools(new LangChainShopTools(intent, collectedShopMap))
                .build();
        String memoryId = seedHistoryMemory("lc4j-agent-stream", historyWithoutLastUser(recentMessages));
        long startTime = System.currentTimeMillis();

        return aiStreamService.stream(emitter -> {
            aiStreamService.sendStart(emitter);
            StringBuilder contentBuilder = new StringBuilder();
            TokenStream tokenStream = agentAssistant.chat(memoryId, systemPrompt, lastMessage.getContent());
            tokenStream.onPartialResponse(delta -> {
                        if (delta == null || delta.isEmpty()) {
                            return;
                        }
                        contentBuilder.append(delta);
                        try {
                            aiStreamService.sendDelta(emitter, delta);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .onError(error -> {
                        recordUsage(userId, "agent", promptTrace, error.getMessage(), startTime, false, error.getMessage());
                        chatMemories.remove(memoryId);
                        try {
                            aiStreamService.sendError(emitter, error.getMessage());
                        } catch (Exception ignored) {
                        }
                        emitter.completeWithError(error);
                    })
                    .onCompleteResponse(response -> {
                        String finalText = safeAssistantReply(contentBuilder.toString());
                        List<AgentReply.ShopCard> replyShops = shopCardAssembler.toShopCardList(collectedShopMap);
                        if (intent.isNeedVoucher()) {
                            shopAgentToolService.enrichShopCardsWithVouchers(replyShops);
                        }
                        AgentReply reply = shopCardAssembler.buildReply(finalText, replyShops);
                        saveAssistantMessage(userId, finalText, replyShops);
                        recordUsage(userId, "agent", promptTrace, finalText, startTime, true, null);
                        chatMemories.remove(memoryId);
                        try {
                            aiStreamService.sendDone(emitter, finalText, reply);
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    })
                    .start();
        });
    }

    private ChatMemory getOrCreateMemory(Object memoryId) {
        return chatMemories.computeIfAbsent(memoryId,
                key -> MessageWindowChatMemory.builder().maxMessages(MAX_REQUEST_MESSAGES + 4).build());
    }

    private String seedHistoryMemory(String prefix, List<AiMessageDTO> history) {
        String memoryId = prefix + ":" + UUID.randomUUID();
        ChatMemory memory = getOrCreateMemory(memoryId);
        if (history != null) {
            for (AiMessageDTO message : history) {
                if ("assistant".equals(message.getRole())) {
                    memory.add(dev.langchain4j.data.message.AiMessage.from(message.getContent()));
                } else {
                    memory.add(dev.langchain4j.data.message.UserMessage.from(message.getContent()));
                }
            }
        }
        return memoryId;
    }

    private interface LangChainAssistant {
        @SystemMessage("{{systemPrompt}}")
        String chat(@MemoryId Object memoryId,
                    @V("systemPrompt") String systemPrompt,
                    @UserMessage String userMessage);
    }

    private interface StreamingLangChainAssistant {
        @SystemMessage("{{systemPrompt}}")
        TokenStream chat(@MemoryId Object memoryId,
                         @V("systemPrompt") String systemPrompt,
                         @UserMessage String userMessage);
    }

    private class LangChainShopTools {
        private final AgentIntent intent;
        private final Map<Long, AgentReply.ShopCard> collectedShopMap;

        private LangChainShopTools(AgentIntent intent, Map<Long, AgentReply.ShopCard> collectedShopMap) {
            this.intent = intent;
            this.collectedShopMap = collectedShopMap;
        }

        @Tool("根据关键词搜索店铺，支持按评分或距离排序")
        public List<Map<String, Object>> searchShop(
                @P("搜索关键词") String keyword,
                @P("排序方式，可选 default、score_desc、distance_asc") String sortBy,
                @P("用户经度") Double x,
                @P("用户纬度") Double y) {
            if (keyword == null || keyword.trim().isEmpty()) {
                return new ArrayList<>();
            }
            String resolvedSortBy = resolveSortBy(sortBy, intent);
            Double resolvedX = x != null ? x : intent.getX();
            Double resolvedY = y != null ? y : intent.getY();
            List<Map<String, Object>> result = shopAgentToolService.searchShop(keyword, resolvedSortBy, resolvedX, resolvedY);
            for (Map<String, Object> shopMap : result) {
                shopCardAssembler.mergeShopCard(collectedShopMap, shopMap);
            }
            return result;
        }

        @Tool("查询指定店铺的优惠券")
        public List<Map<String, Object>> getVoucher(@P("店铺ID") Long shopId) {
            List<Map<String, Object>> vouchers = shopAgentToolService.getVoucher(shopId);
            shopCardAssembler.mergeVoucherCards(collectedShopMap, vouchers);
            return vouchers;
        }

        @Tool("查询店铺详情")
        public Map<String, Object> getShopDetail(@P("店铺ID") Long shopId) {
            Map<String, Object> shopMap = shopAgentToolService.getShopDetail(shopId);
            if (shopMap != null) {
                shopCardAssembler.mergeShopCard(collectedShopMap, shopMap);
            }
            return shopMap;
        }
    }
}
