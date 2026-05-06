package com.lightnote.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lightnote.entity.Conversation;
import com.lightnote.mapper.ConversationMapper;
import com.lightnote.service.IConversationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation> implements IConversationService {
    @Override
    public List<Conversation> queryConversationListByUserId(Long userId) {
        LambdaQueryWrapper<Conversation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Conversation::getUserAId, userId).or().eq(Conversation::getUserBId, userId);
        queryWrapper.orderByDesc(Conversation::getLastTime);
        return this.list(queryWrapper);
    }

    @Override
    public Conversation getOrCreateConversation(Long userAId, Long userBId) {
        // 查询两人之间的会话（顺序不固定，两个方向都要查）
        Conversation conversation = lambdaQuery()
                .and(w -> w.eq(Conversation::getUserAId, userAId)
                        .eq(Conversation::getUserBId, userBId))
                .or(w -> w.eq(Conversation::getUserAId, userBId)
                        .eq(Conversation::getUserBId, userAId))
                .one();

        if (conversation == null) {
            // 不存在则创建
            conversation = new Conversation();
            conversation.setUserAId(userAId);
            conversation.setUserBId(userBId);
            conversation.setLastTime(LocalDateTime.now());
            conversation.setCreatedTime(LocalDateTime.now());
            conversation.setUpdatedTime(LocalDateTime.now());
            save(conversation);
        }
        return conversation;
    }
}
