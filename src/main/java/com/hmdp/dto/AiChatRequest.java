package com.hmdp.dto;

import lombok.Data;
import java.util.List;

@Data
public class AiChatRequest {
    private Long userId;                // 用户ID
    private List<AiMessageDTO> messages; // 完整对话历史
    private String system;               // 系统提示词（可选）
    private String apiUrl;               // 自定义API URL（可选）
}