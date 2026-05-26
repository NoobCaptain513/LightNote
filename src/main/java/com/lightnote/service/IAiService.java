package com.lightnote.service;

import com.lightnote.ai.annotation.AgentFunction;
import com.lightnote.dto.AiAgentRequest;
import com.lightnote.dto.AiChatRequest;
import com.lightnote.dto.Result;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IAiService {
    /**
     * 普通聊天。
     *
     * @param request 聊天请求
     * @return 聊天结果
     */
    Result chat(AiChatRequest request);

    /**
     * 获取当前用户的 AI 聊天历史。
     *
     * @return 聊天历史结果
     */
    @AgentFunction("Retrieves chat history for agent functionality")
    Result getHistory();

    /**
     * Agent 聊天。
     *
     * @param request Agent 请求
     * @return Agent 回复结果
     */
    @AgentFunction("Handles agent chat requests")
    Result agentChat(AiAgentRequest request);

    /**
     * 普通聊天流式返回。
     *
     * @param request 聊天请求
     * @return SSE 输出对象
     */
    @AgentFunction("Handles streaming chat requests")
    SseEmitter streamChat(AiChatRequest request);

    /**
     * Agent 聊天流式返回。
     *
     * @param request Agent 请求
     * @return SSE 输出对象
     */
    @AgentFunction("Handles streaming agent chat requests")
    SseEmitter streamAgentChat(AiAgentRequest request);
}
