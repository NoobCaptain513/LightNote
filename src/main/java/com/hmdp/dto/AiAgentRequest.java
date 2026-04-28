package com.hmdp.dto;

import lombok.Data;
import java.util.List;

@Data
public class AiAgentRequest {
    private List<AiMessageDTO> messages; // 对话历史
    private String system;               // 系统提示词（可选）
    private Double x;                    // 用户经度（可选）
    private Double y;                    // 用户纬度（可选）
}
