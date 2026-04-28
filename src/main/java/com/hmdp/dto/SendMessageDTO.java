package com.hmdp.dto;

import lombok.Data;

@Data
public class SendMessageDTO {
    private Long toUserId;
    private String content;
}
