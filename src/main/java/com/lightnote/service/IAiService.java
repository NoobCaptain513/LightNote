package com.lightnote.service;

import com.lightnote.dto.AiAgentRequest;
import com.lightnote.dto.AiChatRequest;
import com.lightnote.dto.Result;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IAiService {
    Result chat(AiChatRequest request);

    Result getHistory();

    Result agentChat(AiAgentRequest request);

    SseEmitter streamChat(AiChatRequest request);

    SseEmitter streamAgentChat(AiAgentRequest request);
}
