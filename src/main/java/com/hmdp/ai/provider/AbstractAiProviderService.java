package com.hmdp.ai.provider;

import com.hmdp.ai.conversation.AiConversationService;
import com.hmdp.ai.intent.AgentIntentAnalyzer;
import com.hmdp.ai.model.AgentIntent;
import com.hmdp.ai.prompt.AiPromptService;
import com.hmdp.ai.rag.AiRagService;
import com.hmdp.ai.reply.ShopCardAssembler;
import com.hmdp.ai.stream.AiStreamService;
import com.hmdp.ai.tool.ShopAgentToolService;
import com.hmdp.ai.usage.AiUsageLogService;
import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.AiMessageDTO;
import com.hmdp.dto.AgentReply;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiService;
import jakarta.annotation.Resource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public abstract class AbstractAiProviderService implements IAiService {

    protected static final int MAX_REQUEST_MESSAGES = 12;
    protected static final int MAX_HISTORY_MESSAGES = 10;

    @Resource
    protected AiConversationService conversationService;

    @Resource
    protected AgentIntentAnalyzer intentAnalyzer;

    @Resource
    protected AiPromptService promptService;

    @Resource
    protected ShopCardAssembler shopCardAssembler;

    @Resource
    protected ShopAgentToolService shopAgentToolService;

    @Resource
    protected AiRagService aiRagService;

    @Resource
    protected AiStreamService aiStreamService;

    @Resource
    protected AiUsageLogService aiUsageLogService;

    @Override
    public Result getHistory() {
        return conversationService.getHistory(getCurrentUserId(), MAX_HISTORY_MESSAGES);
    }

    @Override
    public SseEmitter streamChat(AiChatRequest request) {
        return aiStreamService.stream(() -> chat(request));
    }

    @Override
    public SseEmitter streamAgentChat(AiAgentRequest request) {
        return aiStreamService.stream(() -> agentChat(request));
    }

    protected Long getCurrentUserId() {
        return conversationService.getCurrentUserId();
    }

    protected List<AiMessageDTO> normalizeRecentMessages(List<AiMessageDTO> messages) {
        return conversationService.normalizeRecentMessages(messages, MAX_REQUEST_MESSAGES);
    }

    protected Result validateUserRequest(Long userId, List<AiMessageDTO> recentMessages) {
        return conversationService.validateUserRequest(userId, recentMessages);
    }

    protected List<AiMessageDTO> historyWithoutLastUser(List<AiMessageDTO> recentMessages) {
        return conversationService.historyWithoutLastUser(recentMessages);
    }

    protected AiMessageDTO getLastMessage(List<AiMessageDTO> recentMessages) {
        return conversationService.getLastMessage(recentMessages);
    }

    protected void saveLastUserMessage(Long userId, List<AiMessageDTO> recentMessages) {
        conversationService.saveLastUserMessage(userId, recentMessages);
    }

    protected void saveAssistantMessage(Long userId, String content) {
        conversationService.saveAssistantMessage(userId, content);
    }

    protected void saveAssistantMessage(Long userId, String content, List<AgentReply.ShopCard> shops) {
        conversationService.saveAssistantMessage(userId, content, shops);
    }

    protected String safeAssistantReply(String content) {
        return conversationService.safeAssistantReply(content);
    }

    protected AgentIntent analyzeIntent(List<AiMessageDTO> recentMessages, AiAgentRequest request) {
        return intentAnalyzer.analyze(recentMessages, request);
    }

    protected String resolveSortBy(String sortBy, AgentIntent intent) {
        return intentAnalyzer.resolveSortBy(sortBy, intent);
    }

    protected String buildChatSystemPrompt(List<AiMessageDTO> recentMessages) {
        String basePrompt = promptService.getDefaultSystemPrompt();
        return promptService.appendRagContext(basePrompt, aiRagService.buildContext(extractLastUserText(recentMessages)));
    }

    protected String buildAgentSystemPrompt(AgentIntent intent, List<AiMessageDTO> recentMessages) {
        String basePrompt = promptService.buildAgentSystemPrompt(intent);
        return promptService.appendRagContext(basePrompt, aiRagService.buildContext(extractLastUserText(recentMessages)));
    }

    protected String getDefaultSystemPrompt() {
        return promptService.getDefaultSystemPrompt();
    }

    protected String buildPromptTrace(String systemPrompt, List<AiMessageDTO> recentMessages) {
        StringBuilder builder = new StringBuilder(systemPrompt == null ? "" : systemPrompt);
        if (recentMessages != null) {
            for (AiMessageDTO message : recentMessages) {
                builder.append("\n[").append(message.getRole()).append("] ").append(message.getContent());
            }
        }
        return builder.toString();
    }

    protected void recordUsage(Long userId,
                               String bizMode,
                               String promptTrace,
                               String completionText,
                               long startTime,
                               boolean success,
                               String errorMsg) {
        aiUsageLogService.record(
                userId,
                bizMode,
                promptTrace,
                completionText,
                System.currentTimeMillis() - startTime,
                success,
                errorMsg
        );
    }

    private String extractLastUserText(List<AiMessageDTO> recentMessages) {
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        return lastMessage == null ? "" : lastMessage.getContent();
    }
}
