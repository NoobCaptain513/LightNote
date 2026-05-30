package com.lightnote.ai.provider.langchain4j;

import com.lightnote.ai.model.AgentIntent;
import com.lightnote.ai.provider.AbstractAiProviderService;
import com.lightnote.ai.rag.AgentRagCandidateService.AgentRagCandidates;
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
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Resource
    private LangChain4jPersistentChatMemoryStore langChain4jMemoryStore;

    @Resource
    private LangChain4jRagContentRetriever langChain4jRagContentRetriever;

    private ChatModel chatModel;
    private LangChainAssistant chatAssistant;
    private StreamingLangChainAssistant streamingChatAssistant;
    private OpenAiStreamingChatModel streamingChatModel;
    private RetrievalAugmentor retrievalAugmentor;
    private final ConcurrentMap<Object, ChatMemory> chatMemories = new ConcurrentHashMap<>();

    /**
     * 初始化LangChain4j服务。
     * 此方法在Bean初始化后调用，用于设置聊天模型和助手。
     */
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

        this.retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .contentRetriever(langChain4jRagContentRetriever)
                .contentInjector(DefaultContentInjector.builder()
                        .metadataKeysToInclude(List.of("title", "sourceType", "sourceId", "score"))
                        .build())
                .build();

        this.chatAssistant = AiServices.builder(LangChainAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(this::getOrCreateMemory)
                .retrievalAugmentor(retrievalAugmentor)
                .storeRetrievedContentInChatMemory(false)
                .build();
        this.streamingChatAssistant = AiServices.builder(StreamingLangChainAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(this::getOrCreateMemory)
                .retrievalAugmentor(retrievalAugmentor)
                .storeRetrievedContentInChatMemory(false)
                .build();
    }

    /**
     * 处理聊天请求。
     * 此方法是聊天功能的一部分。
     * @param request AI聊天请求。
     * @return 聊天结果。
     */
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
        String systemPrompt = buildChatBaseSystemPrompt();
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        String memoryId = prepareMemory("chat", userId, historyWithoutLastUser(recentMessages));

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

    /**
     * 处理聊天流式请求。
     * 此方法是聊天功能的一部分。
     * @param request AI聊天请求。
     * @return 用于流式传输聊天响应的SseEmitter。
     */
    @Override
    public SseEmitter streamChat(AiChatRequest request) {
        Long userId = getCurrentUserId();
        //归一化处理
        List<AiMessageDTO> recentMessages = normalizeRecentMessages(request == null ? null : request.getMessages());
        Result validateResult = validateUserRequest(userId, recentMessages);
        if (validateResult != null) {
            return aiStreamService.errorEmitter(validateResult.getErrorMsg());
        }

        saveLastUserMessage(userId, recentMessages);
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        String systemPrompt = buildChatBaseSystemPrompt();
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        String memoryId = prepareMemory("chat", userId, historyWithoutLastUser(recentMessages));
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
                        emitter.complete();
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

    /**
     * 处理代理聊天请求。
     * 此方法是代理功能的一部分。
     * @param request AI代理请求。
     * @return 代理聊天结果。
     */
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
        Map<Long, AgentReply.ShopCard> collectedShopMap = new LinkedHashMap<>();
        AgentRagCandidates ragCandidates = agentRagCandidateService.preloadShopCandidates(lastMessage.getContent(), intent);
        String systemPrompt = buildAgentSystemPromptWithCandidates(intent, ragCandidates);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        LangChainAssistant agentAssistant = AiServices.builder(LangChainAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(this::getOrCreateMemory)
                .tools(new LangChainShopTools(intent, collectedShopMap))
                .build();
        String memoryId = prepareMemory("agent", userId, historyWithoutLastUser(recentMessages));

        try {
            String content = agentAssistant.chat(memoryId, systemPrompt, lastMessage.getContent());
            String finalText = safeAssistantReply(content);
            ensureShopCardsCollected(lastMessage, intent, collectedShopMap, ragCandidates);
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

    /**
     * 处理代理聊天流式请求。
     * 此方法是代理功能的一部分。
     * @param request AI代理请求。
     * @return 用于流式传输代理聊天响应的SseEmitter。
     */
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
        Map<Long, AgentReply.ShopCard> collectedShopMap = new LinkedHashMap<>();
        AgentRagCandidates ragCandidates = agentRagCandidateService.preloadShopCandidates(lastMessage.getContent(), intent);
        String systemPrompt = buildAgentSystemPromptWithCandidates(intent, ragCandidates);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        StreamingLangChainAssistant agentAssistant = AiServices.builder(StreamingLangChainAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(this::getOrCreateMemory)
                .tools(new LangChainShopTools(intent, collectedShopMap))
                .build();
        String memoryId = prepareMemory("agent", userId, historyWithoutLastUser(recentMessages));
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
                        emitter.complete();
                    })
                    .onCompleteResponse(response -> {
                        String finalText = safeAssistantReply(contentBuilder.toString());
                        ensureShopCardsCollected(lastMessage, intent, collectedShopMap, ragCandidates);
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

    /**
     * 获取或创建聊天内存。
     * 此方法用于为每个聊天会话创建或获取聊天内存。
     * @param memoryId 聊天内存的唯一标识符。
     * @return 聊天内存对象。
     */
    private ChatMemory getOrCreateMemory(Object memoryId) {
        return chatMemories.computeIfAbsent(
                memoryId,
                key -> MessageWindowChatMemory.builder()
                        .id(key)
                        .maxMessages(MAX_REQUEST_MESSAGES + 4)
                        .chatMemoryStore(langChain4jMemoryStore)
                        .alwaysKeepSystemMessageFirst(true)
                        .build()
        );
    }

    /**
     * 准备用户维度的持久化聊天内存。
     * 首次使用时会用前端传入的历史消息做一次兼容性填充。
     * @param scope 聊天内存范围，区分 chat / agent。
     * @param userId 当前用户 ID。
     * @param history 历史记录列表。
     * @return 聊天内存对象的唯一标识符。
     */
    private String prepareMemory(String scope, Long userId, List<AiMessageDTO> history) {
        String memoryId = "lc4j-" + scope + ":" + userId;
        ChatMemory memory = getOrCreateMemory(memoryId);
        if (!memory.messages().isEmpty()) {
            return memoryId;
        }
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

    /**
     * 构建带 RAG 候选店铺摘要的 Agent system prompt。
     *
     * @param intent 当前 Agent 意图
     * @param ragCandidates 前置 RAG 候选结果
     * @return Agent system prompt
     */
    private String buildAgentSystemPromptWithCandidates(AgentIntent intent, AgentRagCandidates ragCandidates) {
        String systemPrompt = buildAgentBaseSystemPrompt(intent);
        if (ragCandidates == null || !StringUtils.hasText(ragCandidates.prompt())) {
            return systemPrompt;
        }
        return systemPrompt + ragCandidates.prompt();
    }

    /**
     * 确保 Agent 回复中有可渲染的店铺卡片。
     * 如果模型没有调用工具，会优先合并前置 RAG 候选卡片，再用用户原句触发工具搜索兜底。
     *
     * @param lastMessage 当前用户最后一条消息
     * @param intent 当前 Agent 意图
     * @param collectedShopMap 本轮已收集的店铺卡片
     * @param ragCandidates 前置 RAG 候选结果
     */
    private void ensureShopCardsCollected(AiMessageDTO lastMessage,
                                          AgentIntent intent,
                                          Map<Long, AgentReply.ShopCard> collectedShopMap,
                                          AgentRagCandidates ragCandidates) {
        if (collectedShopMap == null || !collectedShopMap.isEmpty()) {
            return;
        }
        agentRagCandidateService.mergeCandidatesIfEmpty(collectedShopMap, ragCandidates);
        if (!collectedShopMap.isEmpty()) {
            return;
        }
        String keyword = lastMessage == null ? "" : lastMessage.getContent();
        shopAgentToolExecutor.searchShop(keyword, null, null, null, intent, collectedShopMap);
    }

    /**
     * 聊天助手接口。
     * 此接口定义了与LangChain4j聊天模型交互的方法。
     */
    private interface LangChainAssistant {
        /**
         * 执行非流式 LangChain4j Assistant 对话。
         *
         * @param memoryId 用户维度的记忆 ID
         * @param systemPrompt 本次对话系统提示
         * @param userMessage 当前用户输入
         * @return 模型回复文本
         */
        @SystemMessage("{{systemPrompt}}")
        String chat(@MemoryId Object memoryId,
                    @V("systemPrompt") String systemPrompt,
                    @UserMessage String userMessage);
    }

    /**
     * 流式聊天助手接口。
     * 此接口定义了与LangChain4j流式聊天模型交互的方法。
     */
    private interface StreamingLangChainAssistant {
        /**
         * 执行流式 LangChain4j Assistant 对话。
         *
         * @param memoryId 用户维度的记忆 ID
         * @param systemPrompt 本次对话系统提示
         * @param userMessage 当前用户输入
         * @return LangChain4j TokenStream
         */
        @SystemMessage("{{systemPrompt}}")
        TokenStream chat(@MemoryId Object memoryId,
                         @V("systemPrompt") String systemPrompt,
                         @UserMessage String userMessage);
    }

    /**
     * 聊天助手工具类。
     * 此类包含了与LangChain4j聊天模型交互的工具方法。
     */
    private class LangChainShopTools {
        private final AgentIntent intent;
        private final Map<Long, AgentReply.ShopCard> collectedShopMap;

        private LangChainShopTools(AgentIntent intent, Map<Long, AgentReply.ShopCard> collectedShopMap) {
            this.intent = intent;
            this.collectedShopMap = collectedShopMap;
        }

        /**
         * 根据关键词搜索店铺，并以 LangChain4j typed tool 的结构化对象形式返回。
         *
         * @param keyword 搜索关键词
         * @param sortBy 排序方式
         * @param x 用户经度
         * @param y 用户纬度
         * @return 店铺搜索结果列表
         */
        @Tool("根据关键词搜索店铺，支持按评分或距离排序")
        public List<Map<String, Object>> searchShop(
                @P("搜索关键词") String keyword,
                @P("排序方式") String sortBy,
                @P("用户经度") Double x,
                @P("用户纬度") Double y) {
            return shopAgentToolExecutor.searchShop(keyword, sortBy, x, y, intent, collectedShopMap);
        }

        /**
         * 查询指定店铺的优惠券，并以结构化列表返回给 LangChain4j。
         *
         * @param shopId 店铺 ID
         * @return 优惠券列表
         */
        @Tool("查询指定店铺的优惠券")
        public List<Map<String, Object>> getVoucher(@P("店铺ID") Long shopId) {
            return shopAgentToolExecutor.getVoucher(shopId, collectedShopMap);
        }

        /**
         * 查询指定店铺详情，并以结构化对象返回给 LangChain4j。
         *
         * @param shopId 店铺 ID
         * @return 店铺详情
         */
        @Tool("查询店铺详情")
        public Map<String, Object> getShopDetail(@P("店铺ID") Long shopId) {
            return shopAgentToolExecutor.getShopDetail(shopId, collectedShopMap);
        }
    }
}
