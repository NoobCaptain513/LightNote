package com.hmdp.dto;

import lombok.Data;

@Data
public class ConversationUserDTO {
    private Long conversationId;
    private Long userId;

    private Integer unreadCount;
    private Long lastReadMessageId;

    // 用户信息（扩展字段）
    private String nickname;
    private String icon;
}
