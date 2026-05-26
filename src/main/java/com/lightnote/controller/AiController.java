package com.lightnote.controller;

import com.lightnote.ai.annotation.AgentFunction;
import com.lightnote.dto.AiAgentRequest;
import com.lightnote.dto.AiChatRequest;
import com.lightnote.dto.Result;
import com.lightnote.ai.rag.AiRagService;
import com.lightnote.ai.usage.AiUsageLogService;
import com.lightnote.service.IAiService;
import com.lightnote.utils.UserHolder;
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

    /**
     * 通过API进行普通聊天。
     * @param request 聊天请求。
     * @return 聊天结果。
     */
//    @PostMapping("/chat")
//    public Result chat(@RequestBody AiChatRequest request) {
//        return aiService.chat(request);
//    }

    /**
     * 通过API进行流式聊天。
     * @param request 聊天请求。
     * @return 用于流式传输聊天响应的SseEmitter。
     */
    @PostMapping(value = "/chat/stream", produces = "text/event-stream")
    public SseEmitter chatStream(@RequestBody AiChatRequest request) {
        return aiService.streamChat(request);
    }

    /**
     * 通过API获取代理功能的聊天历史记录。
     * @return 包含聊天历史记录的结果对象。
     */
    @GetMapping("/history")
    @AgentFunction("Retrieves chat history for agent functionality via API")
    public Result getHistory() {
        return aiService.getHistory();
    }

    /**
     * 通过API处理代理聊天请求。
     * @param request AI代理请求。
     * @return 代理聊天结果。
     */
//    @PostMapping("/agent")
//    @AgentFunction("Handles agent chat requests via API")
//    public Result agent(@RequestBody AiAgentRequest request) {
//        return aiService.agentChat(request);
//    }

    /**
     * 通过API处理代理聊天流式请求。
     * @param request AI代理请求。
     * @return 用于流式传输代理聊天响应的SseEmitter。
     */
    @PostMapping(value = "/agent/stream", produces = "text/event-stream")
    @AgentFunction("Handles streaming agent chat requests via API")
    public SseEmitter agentStream(@RequestBody AiAgentRequest request) {
        return aiService.streamAgentChat(request);
    }

    /**
     * 通过API重新构建RAG知识库。
     * @return 包含操作结果的结果对象。
     */
    @PostMapping("/rag/rebuild")
    public Result rebuildRag() {
        return aiRagService.rebuildKnowledgeBase();
    }

    /**
     * 通过API搜索RAG知识库。
     * @param query 搜索查询。
     * @param topK 返回的结果数量。
     * @return 包含搜索结果的结果对象。
     */
    @GetMapping("/rag/search")
    public Result searchRag(@RequestParam("query") String query,
                            @RequestParam(value = "topK", required = false) Integer topK) {
        return aiRagService.searchKnowledge(query, topK);
    }

    /**
     * 通过API获取最近的AI使用记录。
     * @param limit 返回的记录数量。
     * @return 包含最近使用记录的结果对象。
     */
    @GetMapping("/usage/recent")
    public Result recentUsage(@RequestParam(value = "limit", defaultValue = "10") Integer limit) {
        return aiUsageLogService.recent(UserHolder.getUser() == null ? null : UserHolder.getUser().getId(), limit);
    }
}
