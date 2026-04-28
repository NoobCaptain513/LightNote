package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话概要（用于会话列表返回，不对应具体消息明细）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_conversation")
public class Conversation {

    /**
     * 会话ID
     */
    private Long id;

    /**
     * 用户A
     */
    private Long userAId;

    /**
     * 用户B
     */
    private Long userBId;

    /**
     * 最后一条消息ID
     */
    private Long lastMessageId;

    /**
     * 最后消息时间
     */
    private LocalDateTime lastTime;

    /**
     * 创建时间
     */
    private LocalDateTime createdTime;

    /**
     * 更新时间
     */
    private LocalDateTime updatedTime;
}
