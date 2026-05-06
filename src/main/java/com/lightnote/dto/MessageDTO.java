package com.lightnote.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageDTO {
    private Long id;
    private String content;
    private LocalDateTime createTime;

    // 发送者信息
    private Long fromUserId;
    private String fromNickname;
    private String fromIcon;

    // 是否是当前登录用户发送的（前端用于判断消息左右气泡）
    private Boolean isSelf;
}
