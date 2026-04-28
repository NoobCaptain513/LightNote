package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.SendMessageDTO;
import com.hmdp.service.IMessageService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/message")
public class MessageController {

    @Resource
    private IMessageService messageService;

    @PostMapping("/send")
    public Result sendMessage(@RequestBody SendMessageDTO messageDTO) {
        return messageService.sendMessage(messageDTO.getToUserId(), messageDTO.getContent());
    }

    // 查询消息
    @GetMapping("/conversations")
    public Result queryConversations(){
        Long userId = UserHolder.getUser().getId();
        return messageService.queryConversationsById(userId);
    }

    // 查询某会话下的消息列表，并清除当前用户的未读数
    @GetMapping("/conversation/{conversationId}")
    public Result queryMessageByConversationId(
            @PathVariable("conversationId") Long conversationId) {
        return messageService.queryMessageByConversationId(conversationId);
    }

    // 删除消息
    @DeleteMapping("/{messageId}")
    public Result deleteMessageById(@PathVariable("messageId") Long messageId) {
        return messageService.deleteMessageById(messageId);
    }

}
