package com.hmdp.service;

import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IAiService {
    Result chat(AiChatRequest request);

    Result getHistory();

    Result agentChat(AiAgentRequest request);

    SseEmitter streamChat(AiChatRequest request);

    SseEmitter streamAgentChat(AiAgentRequest request);
}
