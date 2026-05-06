package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName("tb_ai_knowledge")
public class AiKnowledge implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String title;
    private String content;
    private String sourceType;
    private Long sourceId;
    private String embedding;
    private Double score;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
