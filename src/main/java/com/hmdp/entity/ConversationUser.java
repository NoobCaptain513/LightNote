package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@TableName("chat_conversation_user")
public class ConversationUser {
    private Long conversationId;

    private Long userId;

    private Integer unreadCount = 0;

    private Long lastReadMessageId = 0L;
}
