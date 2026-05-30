package com.lightnote.ai.provider.springai;

import com.lightnote.ai.model.AgentIntent;
import com.lightnote.ai.provider.AbstractAiProviderService;
import com.lightnote.ai.rag.AgentRagCandidateService.AgentRagCandidates;
import com.lightnote.dto.AiAgentRequest;
import com.lightnote.dto.AiChatRequest;
import com.lightnote.dto.AiMessageDTO;
import com.lightnote.dto.AgentReply;
import com.lightnote.dto.Result;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service("springAiService")
@ConditionalOnProperty(prefix = "ai.provider", name = "type", havingValue = "spring-ai")
public class AiSpringAiServiceImpl extends AbstractAiProviderService {

    @Value("${ai.api-key}")
    private String apiKey;

    @Value("${ai.model}")
    private String model;

    @Value("${ai.compatible-base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String compatibleBaseUrl;

    @Resource
    private SpringAiRagAdvisor springAiRagAdvisor;

    @Resource
    private SpringAiSafetyAdvisor springAiSafetyAdvisor;

    @Resource
    private SpringAiShopToolFactory springAiShopToolFactory;

    private ChatClient chatClient;

    /**
     * 初始化Spring AI客户端
     */
    @PostConstruct
    public void initSpringAiClient() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(compatibleBaseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.2D)
                        .build())
                .build();

        this.chatClient = ChatClient.create(chatModel);
    }

    /**
     * 处理用户聊天请求
     * @param request 用户聊天请求
     * @return 包含AI回复的Result对象
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

        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .advisors(springAiChatAdvisors(recentMessages))
                    .user(lastMessage.getContent())
                    .call()
                    .content();

            String finalText = safeAssistantReply(content);
            saveAssistantMessage(userId, finalText);
            recordUsage(userId, "chat", promptTrace, finalText, startTime, true, null);
            return Result.ok(finalText);
        } catch (Exception e) {
            e.printStackTrace();
            recordUsage(userId, "chat", promptTrace, e.getMessage(), startTime, false, e.getMessage());
            return Result.fail("Spring AI 聊天失败: " + e.getMessage());
        }
    }

    /**
     * 处理用户聊天流请求
     * @param request 用户聊天流请求
     * @return 包含AI回复流的SseEmitter对象
     */
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
        String systemPrompt = buildChatBaseSystemPrompt();
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        long startTime = System.currentTimeMillis();

        return aiStreamService.stream(emitter -> {
            aiStreamService.sendStart(emitter);
            StringBuilder contentBuilder = new StringBuilder();
            AtomicBoolean completed = new AtomicBoolean(false);

            chatClient.prompt()
                    .system(systemPrompt)
                    .advisors(springAiChatAdvisors(recentMessages))
                    .user(lastMessage.getContent())
                    .stream()
                    .content()
                    .subscribe(delta -> {
                        if (delta == null || delta.isEmpty()) {
                            return;
                        }
                        contentBuilder.append(delta);
                        try {
                            aiStreamService.sendDelta(emitter, delta);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, error -> {
                        completed.set(true);
                        recordUsage(userId, "chat", promptTrace, error.getMessage(), startTime, false, error.getMessage());
                        try {
                            aiStreamService.sendError(emitter, error.getMessage());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, () -> {
                        // 流式回调可能存在异常回调和完成回调先后触发的边界情况。
                        // getAndSet(true) 用原子方式判断是否已经处理过收尾逻辑，
                        // 确保保存回复、记录日志、发送 done、关闭 SSE 连接这些操作只执行一次。
                        if (completed.getAndSet(true)) {
                            return;
                        }
                        String finalText = safeAssistantReply(contentBuilder.toString());
                        saveAssistantMessage(userId, finalText);
                        recordUsage(userId, "chat", promptTrace, finalText, startTime, true, null);
                        try {
                            aiStreamService.sendDone(emitter, finalText, finalText);
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    });
        });
    }

    /**
     * 处理用户智能体请求
     * @param request 用户智能体请求
     * @return 包含AI回复的Result对象
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

        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .advisors(springAiAgentAdvisors(recentMessages))
                    .user(lastMessage.getContent())
                    .tools(springAiShopToolFactory.create(intent, collectedShopMap))
                    .call()
                    .content();

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
            return Result.fail("Spring AI Agent 失败: " + e.getMessage());
        }
    }

    /**
     * 处理用户智能体流请求
     * @param request 用户智能体流请求
     * @return 包含AI回复流的SseEmitter对象
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
        //工具结果收集容器
        Map<Long, AgentReply.ShopCard> collectedShopMap = new LinkedHashMap<>();
        AgentRagCandidates ragCandidates = agentRagCandidateService.preloadShopCandidates(lastMessage.getContent(), intent);
        String systemPrompt = buildAgentSystemPromptWithCandidates(intent, ragCandidates);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        long startTime = System.currentTimeMillis();

        return aiStreamService.stream(emitter -> {
            aiStreamService.sendStart(emitter);
            StringBuilder contentBuilder = new StringBuilder();
            AtomicBoolean completed = new AtomicBoolean(false);

            chatClient.prompt()
                    .system(systemPrompt)
                    .advisors(springAiAgentAdvisors(recentMessages))
                    .user(lastMessage.getContent())
                    .tools(springAiShopToolFactory.create(intent, collectedShopMap))
                    .stream()
                    .content()
                    .subscribe(delta -> {
                        if (delta == null || delta.isEmpty()) {
                            return;
                        }
                        contentBuilder.append(delta);
                        try {
                            aiStreamService.sendDelta(emitter, delta);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, error -> {
                        completed.set(true);
                        recordUsage(userId, "agent", promptTrace, error.getMessage(), startTime, false, error.getMessage());
                        try {
                            aiStreamService.sendError(emitter, error.getMessage());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, () -> {
                        if (completed.getAndSet(true)) {
                            return;
                        }
                        String finalText = safeAssistantReply(contentBuilder.toString());
                        ensureShopCardsCollected(lastMessage, intent, collectedShopMap, ragCandidates);
                        List<AgentReply.ShopCard> replyShops = shopCardAssembler.toShopCardList(collectedShopMap);
                        if (intent.isNeedVoucher()) {
                            shopAgentToolService.enrichShopCardsWithVouchers(replyShops);
                        }
                        AgentReply reply = shopCardAssembler.buildReply(finalText, replyShops);
                        saveAssistantMessage(userId, finalText, replyShops);
                        recordUsage(userId, "agent", promptTrace, finalText, startTime, true, null);
                        try {
                            aiStreamService.sendDone(emitter, finalText, reply);
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    });
        });
    }

    /**
     * 组装普通聊天使用的 Spring AI Advisor 链。
     *
     * @param recentMessages 当前请求携带的最近消息
     * @return 安全增强、RAG 增强和窗口记忆 Advisor
     */
    private List<Advisor> springAiChatAdvisors(List<AiMessageDTO> recentMessages) {
        return List.of(
                springAiSafetyAdvisor,
                springAiRagAdvisor,
                MessageChatMemoryAdvisor.builder(buildMemory(historyWithoutLastUser(recentMessages))).build()
        );
    }

    /**
     * 组装 Agent 聊天使用的 Spring AI Advisor 链。
     *
     * @param recentMessages 当前请求携带的最近消息
     * @return 安全增强和窗口记忆 Advisor
     */
    private List<Advisor> springAiAgentAdvisors(List<AiMessageDTO> recentMessages) {
        return List.of(
                springAiSafetyAdvisor,
                MessageChatMemoryAdvisor.builder(buildMemory(historyWithoutLastUser(recentMessages))).build()
        );
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
     * 构建聊天记忆
     * @param history 聊天历史记录
     * @return 包含聊天历史记录的ChatMemory对象
     */
    private ChatMemory buildMemory(List<AiMessageDTO> history) {
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();
        if (history == null || history.isEmpty()) {
            return memory;
        }
        List<Message> chatMessages = new ArrayList<>();
        for (AiMessageDTO message : history) {
            chatMessages.add("assistant".equals(message.getRole())
                    ? new AssistantMessage(message.getContent())
                    : new UserMessage(message.getContent()));
        }
        memory.add(ChatMemory.DEFAULT_CONVERSATION_ID, chatMessages);
        return memory;
    }
}
