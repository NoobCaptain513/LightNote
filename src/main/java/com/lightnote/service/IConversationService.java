package com.lightnote.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lightnote.entity.Conversation;

import java.util.List;

public interface IConversationService extends IService<Conversation> {
    List<Conversation> queryConversationListByUserId(Long userId);

    Conversation getOrCreateConversation(Long userAId, Long userBId);
}
