package com.lightnote.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.lightnote.dto.Result;
import com.lightnote.entity.Message;

public interface IMessageService extends IService<Message> {
    Result sendMessage(Long toUserId, String content);

    Result queryConversationsById(Long id);


    Result queryMessageByConversationId(Long conversationId);

    Result deleteMessageById(Long messageId);
}
