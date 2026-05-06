package com.hmdp.controller;

import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.ai.rag.AiRagService;
import com.hmdp.ai.usage.AiUsageLogService;
import com.hmdp.service.IAiService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private IAiService aiService;

    @Resource
    private AiRagService aiRagService;

    @Resource
    private AiUsageLogService aiUsageLogService;

    @PostMapping("/chat")
    public Result chat(@RequestBody AiChatRequest request) {
        return aiService.chat(request);
    }

    @PostMapping(value = "/chat/stream", produces = "text/event-stream")
    public SseEmitter chatStream(@RequestBody AiChatRequest request) {
        return aiService.streamChat(request);
    }

    @GetMapping("/history")
    public Result getHistory() {
        return aiService.getHistory();
    }

    @PostMapping("/agent")
    public Result agent(@RequestBody AiAgentRequest request) {
        return aiService.agentChat(request);
    }

    @PostMapping(value = "/agent/stream", produces = "text/event-stream")
    public SseEmitter agentStream(@RequestBody AiAgentRequest request) {
        return aiService.streamAgentChat(request);
    }

    @PostMapping("/rag/rebuild")
    public Result rebuildRag() {
        return aiRagService.rebuildKnowledgeBase();
    }

    @GetMapping("/rag/search")
    public Result searchRag(@RequestParam("query") String query,
                            @RequestParam(value = "topK", required = false) Integer topK) {
        return aiRagService.searchKnowledge(query, topK);
    }

    @GetMapping("/usage/recent")
    public Result recentUsage(@RequestParam(value = "limit", defaultValue = "10") Integer limit) {
        return aiUsageLogService.recent(UserHolder.getUser() == null ? null : UserHolder.getUser().getId(), limit);
    }
}
