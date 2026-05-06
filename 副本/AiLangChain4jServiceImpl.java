package com.hmdp.service.impl;

import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiMessageDTO;
import com.hmdp.dto.AgentReply;
import com.hmdp.dto.Result;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

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

    @Value("${ai.compatible-base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String compatibleBaseUrl;

    private ChatModel chatModel;
    private LangChainAssistant chatAssistant;
    private final ConcurrentMap<Object, ChatMemory> chatMemories = new ConcurrentHashMap<>();

    @PostConstruct
    public void initLangChain4j() {
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(compatibleBaseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .timeout(Duration.ofSeconds(60))
                .build();

        this.chatAssistant = AiServices.builder(LangChainAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(this::getOrCreateMemory)
                .build();
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
        String memoryId = seedHistoryMemory("lc4j-chat", historyWithoutLastUser(recentMessages));

        try {
            String content = chatAssistant.chat(memoryId, DEFAULT_SYSTEM_PROMPT, lastMessage.getContent());
            String finalText = safeAssistantReply(content);
            saveAssistantMessage(userId, finalText);
            return Result.ok(finalText);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("LangChain4j 聊天失败: " + e.getMessage());
        } finally {
            chatMemories.remove(memoryId);
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
        LangChainAssistant agentAssistant = AiServices.builder(LangChainAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(this::getOrCreateMemory)
                .tools(new LangChainShopTools(intent, collectedShopMap))
                .build();
        String memoryId = seedHistoryMemory("lc4j-agent", historyWithoutLastUser(recentMessages));

        try {
            String content = agentAssistant.chat(memoryId, buildAgentSystemPrompt(intent), lastMessage.getContent());
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
            return Result.fail("LangChain4j Agent 失败: " + e.getMessage());
        } finally {
            chatMemories.remove(memoryId);
        }
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

        @Tool("查询指定店铺的优惠券")
        public List<Map<String, Object>> getVoucher(@P("店铺ID") Long shopId) {
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

        @Tool("查询店铺详情")
        public Map<String, Object> getShopDetail(@P("店铺ID") Long shopId) {
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
