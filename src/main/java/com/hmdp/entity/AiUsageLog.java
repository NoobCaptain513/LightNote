package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName("tb_ai_usage_log")
public class AiUsageLog implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String providerType;
    private String modelName;
    private String bizMode;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private BigDecimal estimatedCost;
    private Long latencyMs;
    private Boolean success;
    private String errorMsg;
    private String promptPreview;
    private LocalDateTime createTime;
}
