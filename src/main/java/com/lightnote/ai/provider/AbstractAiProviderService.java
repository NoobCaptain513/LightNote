package com.lightnote.ai.provider;

import com.lightnote.ai.annotation.AgentFunction;
import com.lightnote.ai.conversation.AiConversationService;
import com.lightnote.ai.intent.AgentIntentAnalyzer;
import com.lightnote.ai.model.AgentIntent;
import com.lightnote.ai.prompt.AiPromptService;
import com.lightnote.ai.rag.AiRagService;
import com.lightnote.ai.reply.ShopCardAssembler;
import com.lightnote.ai.stream.AiStreamService;
import com.lightnote.ai.tool.ShopAgentToolExecutor;
import com.lightnote.ai.tool.ShopAgentToolService;
import com.lightnote.ai.usage.AiUsageLogService;
import com.lightnote.dto.AiAgentRequest;
import com.lightnote.dto.AiChatRequest;
import com.lightnote.dto.AiMessageDTO;
import com.lightnote.dto.AgentReply;
import com.lightnote.dto.Result;
import com.lightnote.service.IAiService;
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
    protected ShopAgentToolExecutor shopAgentToolExecutor;

    @Resource
    protected AiRagService aiRagService;

    @Resource
    protected AiStreamService aiStreamService;

    @Resource
    protected AiUsageLogService aiUsageLogService;

    @Override
    /**
     * 获取代理功能的聊天历史记录。
     * 此方法是代理功能的一部分。
     * @return 包含聊天历史记录的结果对象。
     */
    @AgentFunction("Retrieves chat history for agent functionality")
    public Result getHistory() {
        return conversationService.getHistory(getCurrentUserId(), MAX_HISTORY_MESSAGES);
    }

    /**
     * 处理聊天流式请求。
     * 此方法是代理功能的一部分。
     * @param request AI聊天请求。
     * @return 用于流式传输聊天响应的SseEmitter。
     */
    @Override
    public SseEmitter streamChat(AiChatRequest request) {
        return aiStreamService.stream(() -> chat(request));
    }

    @Override
    /**
     * 处理代理聊天流式请求。
     * 此方法是代理功能的一部分。
     * @param request AI代理请求。
     * @return 用于流式传输代理聊天响应的SseEmitter。
     */
    @AgentFunction("Handles streaming agent chat requests")
    public SseEmitter streamAgentChat(AiAgentRequest request) {
        return aiStreamService.stream(() -> agentChat(request));
    }

    @Override
    /**
     * 处理代理聊天请求。
     * 此方法是代理功能的一部分。
     * @param request AI代理请求。
     * @return 代理聊天结果。
     */
    @AgentFunction("Handles agent chat requests")
    public abstract Result agentChat(AiAgentRequest request);

    /**
     * 获取当前用户ID。
     * @return 当前用户ID。
     */
    protected Long getCurrentUserId() {
        return conversationService.getCurrentUserId();
    }

    /**
     * 归一化最近的消息列表，确保不超过最大请求消息数。
     * @param messages 原始AI消息列表。
     * @return 归一化后的AI消息列表。
     */
    protected List<AiMessageDTO> normalizeRecentMessages(List<AiMessageDTO> messages) {
        return conversationService.normalizeRecentMessages(messages, MAX_REQUEST_MESSAGES);
    }

    /**
     * 验证用户请求是否有效。
     * @param userId 用户ID。
     * @param recentMessages 最近的AI消息列表。
     * @return 验证结果。
     */
    protected Result validateUserRequest(Long userId, List<AiMessageDTO> recentMessages) {
        return conversationService.validateUserRequest(userId, recentMessages);
    }

    /**
     * 获取最近的用户消息列表，不包括最近的用户消息。
     * @param recentMessages 最近的AI消息列表。
     * @return 不包括最近用户消息的AI消息列表。
     */
    protected List<AiMessageDTO> historyWithoutLastUser(List<AiMessageDTO> recentMessages) {
        return conversationService.historyWithoutLastUser(recentMessages);
    }

    /**
     * 获取最近的用户消息。
     * @param recentMessages 最近的AI消息列表。
     * @return 最近的用户消息DTO。
     */
    protected AiMessageDTO getLastMessage(List<AiMessageDTO> recentMessages) {
        return conversationService.getLastMessage(recentMessages);
    }

    /**
     * 保存最近的用户消息。
     * @param userId 用户ID。
     * @param recentMessages 最近的AI消息列表。
     */
    protected void saveLastUserMessage(Long userId, List<AiMessageDTO> recentMessages) {
        conversationService.saveLastUserMessage(userId, recentMessages);
    }

    /**
     * 保存助手消息，不包含商店卡片。
     * @param userId 用户ID。
     * @param content 助手回复内容。
     */
    protected void saveAssistantMessage(Long userId, String content) {
        conversationService.saveAssistantMessage(userId, content);
    }

    /**
     * 保存助手消息，包含商店卡片。
     * @param userId 用户ID。
     * @param content 助手回复内容。
     * @param shops 关联的商店卡片列表。
     */
    protected void saveAssistantMessage(Long userId, String content, List<AgentReply.ShopCard> shops) {
        conversationService.saveAssistantMessage(userId, content, shops);
    }

    /**
     * 安全地处理助手回复，防止格式错误。
     * @param content 助手回复内容。
     * @return 安全处理后的助手回复字符串。
     */
    protected String safeAssistantReply(String content) {
        return conversationService.safeAssistantReply(content);
    }

    /**
     * 分析代理意图，根据最近的消息和请求。
     * @param recentMessages 最近的AI消息列表。
     * @param request AI代理请求。
     * @return 分析后的代理意图对象。
     */
    protected AgentIntent analyzeIntent(List<AiMessageDTO> recentMessages, AiAgentRequest request) {
        return intentAnalyzer.analyze(recentMessages, request);
    }

    /**
     * 构建聊天系统提示，包含RAG上下文。
     * @param recentMessages 最近的AI消息列表。
     * @return 包含RAG上下文的聊天系统提示字符串。
     */
    protected String buildChatSystemPrompt(List<AiMessageDTO> recentMessages) {
        return promptService.appendRagContext(buildChatBaseSystemPrompt(), aiRagService.buildContext(extractLastUserText(recentMessages)));
    }

    /**
     * 构建代理系统提示，包含RAG上下文。
     * @param intent 代理意图。
     * @param recentMessages 最近的AI消息列表。
     * @return 包含RAG上下文的代理系统提示字符串。
     */
    protected String buildAgentSystemPrompt(AgentIntent intent, List<AiMessageDTO> recentMessages) {
        return promptService.appendRagContext(buildAgentBaseSystemPrompt(intent), aiRagService.buildContext(extractLastUserText(recentMessages)));
    }

    /**
     * 构建不含 RAG 上下文的普通聊天系统提示，供框架原生 RAG 扩展点使用。
     *
     * @return 基础聊天系统提示
     */
    protected String buildChatBaseSystemPrompt() {
        return promptService.getDefaultSystemPrompt();
    }

    /**
     * 构建不含 RAG 上下文的 Agent 系统提示，供框架原生 RAG 扩展点使用。
     *
     * @param intent 智能体意图
     * @return 基础 Agent 系统提示
     */
    protected String buildAgentBaseSystemPrompt(AgentIntent intent) {
        return promptService.buildAgentSystemPrompt(intent);
    }

    /**
     * 构建完整的提示跟踪，包含系统提示和用户消息。
     * @param systemPrompt 系统提示。
     * @param recentMessages 最近的AI消息列表。
     * @return 完整的提示跟踪字符串。
     */
    protected String buildPromptTrace(String systemPrompt, List<AiMessageDTO> recentMessages) {
        StringBuilder builder = new StringBuilder(systemPrompt == null ? "" : systemPrompt);
        if (recentMessages != null) {
            for (AiMessageDTO message : recentMessages) {
                builder.append("\n[").append(message.getRole()).append("] ").append(message.getContent());
            }
        }
        return builder.toString();
    }

    /**
     * 记录AI使用日志。
     * @param userId 用户ID。
     * @param bizMode 业务模式（如"chat"或"agent"）。
     * @param promptTrace 完整的提示跟踪（包含系统提示和用户消息）。
     * @param completionText 完成的文本响应。
     * @param startTime 开始时间（毫秒）。
     * @param success 是否成功。
     * @param errorMsg 错误消息（如果有）。
     */
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

    /**
     * 从最近消息中提取最后一个用户消息的文本内容。
     * @param recentMessages 最近的AI消息列表。
     * @return 最后一个用户消息的文本内容。
     */
    private String extractLastUserText(List<AiMessageDTO> recentMessages) {
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        return lastMessage == null ? "" : lastMessage.getContent();
    }
}
