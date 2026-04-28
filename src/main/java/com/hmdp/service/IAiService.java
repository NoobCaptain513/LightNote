package com.hmdp.service;

import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;

public interface IAiService {
    Result chat(AiChatRequest request);

    Result getHistory();

    Result agentChat(AiAgentRequest request);
}
