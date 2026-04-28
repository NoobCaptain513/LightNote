package com.hmdp.controller;

import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private IAiService aiService;

    @PostMapping("/chat")
    public Result chat(@RequestBody AiChatRequest request) {
        return aiService.chat(request);
    }

    @GetMapping("/history")
    public Result getHistory() {
        return aiService.getHistory();
    }

    @PostMapping("/agent")
    public Result agent(@RequestBody AiAgentRequest request) {
        return aiService.agentChat(request);
    }
}
