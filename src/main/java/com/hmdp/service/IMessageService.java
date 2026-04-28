package com.hmdp.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Message;

public interface IMessageService extends IService<Message> {
    Result sendMessage(Long toUserId, String content);

    Result queryConversationsById(Long id);


    Result queryMessageByConversationId(Long conversationId);

    Result deleteMessageById(Long messageId);
}
