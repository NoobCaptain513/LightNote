package com.hmdp.ai.provider.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.intent.AgentIntentAnalyzer;
import com.hmdp.ai.model.AgentIntent;
import com.hmdp.ai.provider.AbstractAiProviderService;
import com.hmdp.ai.reply.ShopCardAssembler;
import com.hmdp.ai.tool.ShopAgentToolService;
import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiMessageDTO;
import com.hmdp.dto.AgentReply;
import com.hmdp.dto.Result;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
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
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
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

    private ChatClient chatClient;

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

        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .advisors(MessageChatMemoryAdvisor.builder(buildMemory(historyWithoutLastUser(recentMessages))).build())
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
        long startTime = System.currentTimeMillis();

        return aiStreamService.stream(emitter -> {
            aiStreamService.sendStart(emitter);
            StringBuilder contentBuilder = new StringBuilder();
            AtomicBoolean completed = new AtomicBoolean(false);

            chatClient.prompt()
                    .system(systemPrompt)
                    .advisors(MessageChatMemoryAdvisor.builder(buildMemory(historyWithoutLastUser(recentMessages))).build())
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
        String systemPrompt = buildAgentSystemPrompt(intent, recentMessages);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);

        try {
            SpringAiShopTools tools = new SpringAiShopTools(intent, collectedShopMap, shopAgentToolService, shopCardAssembler, intentAnalyzer);
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .advisors(MessageChatMemoryAdvisor.builder(buildMemory(historyWithoutLastUser(recentMessages))).build())
                    .user(lastMessage.getContent())
                    .tools(tools)
                    .call()
                    .content();

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
            return Result.fail("Spring AI Agent 失败: " + e.getMessage());
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
        Map<Long, AgentReply.ShopCard> collectedShopMap = new LinkedHashMap<>();
        SpringAiShopTools tools = new SpringAiShopTools(intent, collectedShopMap, shopAgentToolService, shopCardAssembler, intentAnalyzer);
        String systemPrompt = buildAgentSystemPrompt(intent, recentMessages);
        String promptTrace = buildPromptTrace(systemPrompt, recentMessages);
        long startTime = System.currentTimeMillis();

        return aiStreamService.stream(emitter -> {
            aiStreamService.sendStart(emitter);
            StringBuilder contentBuilder = new StringBuilder();
            AtomicBoolean completed = new AtomicBoolean(false);

            chatClient.prompt()
                    .system(systemPrompt)
                    .advisors(MessageChatMemoryAdvisor.builder(buildMemory(historyWithoutLastUser(recentMessages))).build())
                    .user(lastMessage.getContent())
                    .tools(tools)
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

    private static class SpringAiShopTools {
        private final AgentIntent intent;
        private final Map<Long, AgentReply.ShopCard> collectedShopMap;
        private final ShopAgentToolService shopAgentToolService;
        private final ShopCardAssembler shopCardAssembler;
        private final AgentIntentAnalyzer intentAnalyzer;
        private final ObjectMapper objectMapper;

        private SpringAiShopTools(AgentIntent intent,
                                  Map<Long, AgentReply.ShopCard> collectedShopMap,
                                  ShopAgentToolService shopAgentToolService,
                                  ShopCardAssembler shopCardAssembler,
                                  AgentIntentAnalyzer intentAnalyzer) {
            this.intent = intent;
            this.collectedShopMap = collectedShopMap;
            this.shopAgentToolService = shopAgentToolService;
            this.shopCardAssembler = shopCardAssembler;
            this.intentAnalyzer = intentAnalyzer;
            this.objectMapper = new ObjectMapper();
        }

        @Tool(description = "根据关键词搜索店铺，支持按评分或距离排序")
        public String searchShop(
                @ToolParam(description = "搜索关键词") String keyword,
                @ToolParam(description = "排序方式") String sortBy,
                @ToolParam(description = "用户经度") Double x,
                @ToolParam(description = "用户纬度") Double y) {
            if (keyword == null || keyword.trim().isEmpty()) {
                return "[]";
            }
            String resolvedSortBy = intentAnalyzer.resolveSortBy(sortBy, intent);
            Double resolvedX = x != null ? x : intent.getX();
            Double resolvedY = y != null ? y : intent.getY();
            List<Map<String, Object>> result = shopAgentToolService.searchShop(keyword, resolvedSortBy, resolvedX, resolvedY);
            for (Map<String, Object> shopMap : result) {
                shopCardAssembler.mergeShopCard(collectedShopMap, shopMap);
            }
            try {
                return objectMapper.writeValueAsString(result);
            } catch (Exception e) {
                return "[]";
            }
        }

        @Tool(description = "查询指定店铺的优惠券")
        public String getVoucher(@ToolParam(description = "店铺ID") Long shopId) {
            List<Map<String, Object>> vouchers = shopAgentToolService.getVoucher(shopId);
            shopCardAssembler.mergeVoucherCards(collectedShopMap, vouchers);
            try {
                return objectMapper.writeValueAsString(vouchers);
            } catch (Exception e) {
                return "[]";
            }
        }

        @Tool(description = "查询店铺详情")
        public String getShopDetail(@ToolParam(description = "店铺ID") Long shopId) {
            Map<String, Object> shopMap = shopAgentToolService.getShopDetail(shopId);
            if (shopMap != null) {
                shopCardAssembler.mergeShopCard(collectedShopMap, shopMap);
            }
            try {
                return objectMapper.writeValueAsString(shopMap);
            } catch (Exception e) {
                return "{}";
            }
        }
    }
}
