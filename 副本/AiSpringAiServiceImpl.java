package com.hmdp.service.impl;

import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiMessageDTO;
import com.hmdp.dto.AgentReply;
import com.hmdp.dto.Result;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Long userId = getCurrentUserId();
        List<AiMessageDTO> recentMessages = normalizeRecentMessages(request == null ? null : request.getMessages());
        Result validateResult = validateAndPrepareUserRequest(userId, recentMessages);
        if (validateResult != null) {
            return validateResult;
        }

        saveLastUserMessage(userId, recentMessages);
        AiMessageDTO lastMessage = getLastMessage(recentMessages);

        try {
            String content = chatClient.prompt()
                    .system(DEFAULT_SYSTEM_PROMPT)
                    .advisors(MessageChatMemoryAdvisor.builder(buildMemory(historyWithoutLastUser(recentMessages))).build())
                    .user(lastMessage.getContent())
                    .call()
                    .content();

            String finalText = safeAssistantReply(content);
            saveAssistantMessage(userId, finalText);
            return Result.ok(finalText);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("Spring AI 聊天失败: " + e.getMessage());
        }
    }

    @Override
    public Result agentChat(AiAgentRequest request) {
        Long userId = getCurrentUserId();
        List<AiMessageDTO> recentMessages = normalizeRecentMessages(request == null ? null : request.getMessages());
        Result validateResult = validateAndPrepareUserRequest(userId, recentMessages);
        if (validateResult != null) {
            return validateResult;
        }

        saveLastUserMessage(userId, recentMessages);
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        AgentIntent intent = analyzeIntent(recentMessages, request);
        Map<Long, AgentReply.ShopCard> collectedShopMap = new LinkedHashMap<>();

        try {
            SpringAiShopTools tools = new SpringAiShopTools(intent, collectedShopMap);
            String content = chatClient.prompt()
                    .system(buildAgentSystemPrompt(intent))
                    .advisors(MessageChatMemoryAdvisor.builder(buildMemory(historyWithoutLastUser(recentMessages))).build())
                    .user(lastMessage.getContent())
                    .tools(tools)
                    .call()
                    .content();

            String finalText = safeAssistantReply(content);
            List<AgentReply.ShopCard> replyShops = new ArrayList<>(collectedShopMap.values());
            if (intent.needVoucher) {
                enrichShopCardsWithVouchers(replyShops);
            }

            saveAssistantMessage(userId, finalText, replyShops);
            AgentReply reply = new AgentReply();
            reply.setText(finalText);
            reply.setShops(replyShops);
            return Result.ok(reply);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("Spring AI Agent 失败: " + e.getMessage());
        }
    }

    private MessageWindowChatMemory buildMemory(List<AiMessageDTO> history) {
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(MAX_REQUEST_MESSAGES + 4)
                .build();
        if (history == null || history.isEmpty()) {
            return memory;
        }
        for (AiMessageDTO message : history) {
            Message chatMessage = "assistant".equals(message.getRole())
                    ? new AssistantMessage(message.getContent())
                    : new UserMessage(message.getContent());
            memory.add(ChatMemory.DEFAULT_CONVERSATION_ID, chatMessage);
        }
        return memory;
    }

    private class SpringAiShopTools {
        private final AgentIntent intent;
        private final Map<Long, AgentReply.ShopCard> collectedShopMap;

        private SpringAiShopTools(AgentIntent intent, Map<Long, AgentReply.ShopCard> collectedShopMap) {
            this.intent = intent;
            this.collectedShopMap = collectedShopMap;
        }

        @Tool(description = "根据关键词搜索店铺，支持按评分或距离排序")
        public List<Map<String, Object>> searchShop(
                @ToolParam(description = "搜索关键词") String keyword,
                @ToolParam(description = "排序方式，可选 default、score_desc、distance_asc") String sortBy,
                @ToolParam(description = "用户经度") Double x,
                @ToolParam(description = "用户纬度") Double y) {
            if (keyword == null || keyword.trim().isEmpty()) {
                return new ArrayList<>();
            }
            String resolvedSortBy = resolveSortBy(sortBy, intent);
            Double resolvedX = x != null ? x : intent.x;
            Double resolvedY = y != null ? y : intent.y;
            List<com.hmdp.entity.Shop> shops = searchShopsByKeywordOrType(keyword, resolvedSortBy, resolvedX, resolvedY);

            List<Map<String, Object>> result = new ArrayList<>();
            for (com.hmdp.entity.Shop shop : shops) {
                Map<String, Object> shopMap = toShopMap(shop);
                result.add(shopMap);
                mergeShopCard(collectedShopMap, shopMap);
            }
            return result;
        }

        @Tool(description = "查询指定店铺的优惠券")
        public List<Map<String, Object>> getVoucher(@ToolParam(description = "店铺ID") Long shopId) {
            if (shopId == null) {
                return new ArrayList<>();
            }
            Result voucherResult = voucherService.queryVoucherOfShop(shopId);
            Object data = voucherResult.getData();
            List<Map<String, Object>> vouchers = new ArrayList<>();
            if (data instanceof List) {
                for (Object item : (List<?>) data) {
                    if (item instanceof com.hmdp.entity.Voucher) {
                        vouchers.add(toVoucherMap((com.hmdp.entity.Voucher) item));
                    } else if (item instanceof Map) {
                        vouchers.add((Map<String, Object>) item);
                    }
                }
            }
            mergeVoucherCards(collectedShopMap, vouchers);
            return vouchers;
        }

        @Tool(description = "查询店铺详情")
        public Map<String, Object> getShopDetail(@ToolParam(description = "店铺ID") Long shopId) {
            if (shopId == null) {
                return new LinkedHashMap<>();
            }
            com.hmdp.entity.Shop shop = shopService.getById(shopId);
            if (shop == null) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> shopMap = toShopMap(shop);
            mergeShopCard(collectedShopMap, shopMap);
            return shopMap;
        }
    }
}
