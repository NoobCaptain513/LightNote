package com.lightnote.dto;

import lombok.Data;

import java.util.List;

@Data
public class AiMessageDTO {
    private String role;    // user / assistant
    private String content;
    private List<AgentReply.ShopCard> shops;
}
