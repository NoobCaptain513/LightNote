package com.lightnote.dto;

import lombok.Data;

@Data
public class SendMessageDTO {
    private Long toUserId;
    private String content;
}
