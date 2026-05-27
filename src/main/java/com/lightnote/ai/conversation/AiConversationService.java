package com.lightnote.ai.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightnote.dto.AiMessageDTO;
import com.lightnote.dto.AgentReply;
import com.lightnote.dto.Result;
import com.lightnote.dto.UserDTO;
import com.lightnote.entity.AiMessage;
import com.lightnote.mapper.AiMessageMapper;
import com.lightnote.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiConversationService {

    private static final String AGENT_PAYLOAD_PREFIX = "__AGENT_REPLY__";

    private final AiMessageMapper aiMessageMapper;
    private final ObjectMapper objectMapper;

    /**
     * 获取当前登录用户 ID。
     *
     * @return 当前用户 ID，未登录时返回 {@code null}
     */
    public Long getCurrentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }

    /**
     * 校验用户请求是否合法。
     *
     * @param userId 当前用户 ID
     * @param recentMessages 最近消息列表
     * @return 校验失败时返回失败结果，校验通过时返回 {@code null}
     */
    public Result validateUserRequest(Long userId, List<AiMessageDTO> recentMessages) {
        if (userId == null) {
            return Result.fail("用户未登录");
        }
        if (recentMessages == null || recentMessages.isEmpty()) {
            return Result.fail("消息内容不能为空");
        }
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        if (lastMessage == null || lastMessage.getContent() == null || lastMessage.getContent().trim().isEmpty()) {
            return Result.fail("消息内容不能为空");
        }
        return null;
    }

    /**
     * 标准化最近消息列表，过滤空消息并限制最大条数。
     *
     * @param messages 原始消息列表
     * @param maxMessages 最大保留条数
     * @return 处理后的消息列表
     */
    public List<AiMessageDTO> normalizeRecentMessages(List<AiMessageDTO> messages, int maxMessages) {
        List<AiMessageDTO> normalized = new ArrayList<>();
        if (messages == null) {
            return normalized;
        }

        for (AiMessageDTO message : messages) {
            if (message == null || message.getContent() == null) {
                continue;
            }
            String content = message.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            AiMessageDTO dto = new AiMessageDTO();
            dto.setRole("assistant".equals(message.getRole()) ? "assistant" : "user");
            dto.setContent(content);
            dto.setShops(message.getShops());
            normalized.add(dto);
        }

        if (normalized.size() <= maxMessages) {
            return normalized;
        }
        return new ArrayList<>(normalized.subList(normalized.size() - maxMessages, normalized.size()));
    }

    /**
     * 获取用户历史消息结果。
     *
     * @param userId 当前用户 ID
     * @param maxHistoryMessages 最大历史消息条数
     * @return 历史消息结果
     */
    public Result getHistory(Long userId, int maxHistoryMessages) {
        if (userId == null) {
            return Result.fail("用户未登录");
        }
        return Result.ok(getHistoryMessages(userId, maxHistoryMessages));
    }

    /**
     * 查询用户历史消息列表。
     *
     * @param userId 当前用户 ID
     * @param maxHistoryMessages 最大历史消息条数
     * @return 历史消息列表
     */
    public List<AiMessageDTO> getHistoryMessages(Long userId, int maxHistoryMessages) {
        LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiMessage::getUserId, userId)
                .in(AiMessage::getRole, "user", "assistant")
                .orderByDesc(AiMessage::getCreateTime)
                .last("limit " + maxHistoryMessages);
        List<AiMessage> messages = aiMessageMapper.selectList(wrapper);
        Collections.reverse(messages);

        List<AiMessageDTO> history = new ArrayList<>(messages.size());
        for (AiMessage message : messages) {
            history.add(toHistoryMessage(message));
        }
        return history;
    }

    /**
     * 获取去掉最后一条用户消息后的历史消息。
     *
     * @param recentMessages 最近消息列表
     * @return 不包含最后一条用户消息的列表
     */
    public List<AiMessageDTO> historyWithoutLastUser(List<AiMessageDTO> recentMessages) {
        if (recentMessages == null || recentMessages.size() <= 1) {
            return new ArrayList<>();
        }
        return new ArrayList<>(recentMessages.subList(0, recentMessages.size() - 1));
    }

    /**
     * 获取最后一条消息。
     *
     * @param recentMessages 最近消息列表
     * @return 最后一条消息；如果为空则返回 {@code null}
     */
    public AiMessageDTO getLastMessage(List<AiMessageDTO> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return null;
        }
        return recentMessages.get(recentMessages.size() - 1);
    }

    /**
     * 保存最后一条用户消息。
     *
     * @param userId 当前用户 ID
     * @param recentMessages 最近消息列表
     */
    public void saveLastUserMessage(Long userId, List<AiMessageDTO> recentMessages) {
        AiMessageDTO lastUserMessage = getLastMessage(recentMessages);
        if (lastUserMessage == null || !"user".equals(lastUserMessage.getRole())) {
            return;
        }
        aiMessageMapper.insert(new AiMessage()
                .setUserId(userId)
                .setRole("user")
                .setContent(lastUserMessage.getContent())
                .setCreateTime(LocalDateTime.now()));
    }

    /**
     * 保存助手消息。
     *
     * @param userId 当前用户 ID
     * @param content 助手回复内容
     */
    public void saveAssistantMessage(Long userId, String content) {
        saveAssistantMessage(userId, content, null);
    }

    /**
     * 保存助手消息，并附带店铺卡片数据。
     *
     * @param userId 当前用户 ID
     * @param content 助手回复内容
     * @param shops 店铺卡片列表
     */
    public void saveAssistantMessage(Long userId, String content, List<AgentReply.ShopCard> shops) {
        aiMessageMapper.insert(new AiMessage()
                .setUserId(userId)
                .setRole("assistant")
                .setContent(encodeAssistantContent(content, shops))
                .setCreateTime(LocalDateTime.now()));
    }

    /**
     * 兜底处理助手回复，避免返回空字符串。
     *
     * @param content 助手回复内容
     * @return 非空回复文本
     */
    public String safeAssistantReply(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "我已经查到一些结果，但当前总结为空，你可以换一种问法继续问我。";
        }
        return content.trim();
    }

    /**
     * 将持久化消息转换为历史消息 DTO，并在需要时解析 Agent 附加数据。
     *
     * @param message 持久化消息实体
     * @return 历史消息 DTO
     */
    private AiMessageDTO toHistoryMessage(AiMessage message) {
        AiMessageDTO dto = new AiMessageDTO();
        dto.setRole(message.getRole());
        dto.setContent(message.getContent());
        if (!"assistant".equals(message.getRole())
                || message.getContent() == null
                || !message.getContent().startsWith(AGENT_PAYLOAD_PREFIX)) {
            return dto;
        }

        try {
            String payloadJson = message.getContent().substring(AGENT_PAYLOAD_PREFIX.length());
            AgentReply payload = objectMapper.readValue(payloadJson, AgentReply.class);
            dto.setContent(payload.getText());
            dto.setShops(payload.getShops());
        } catch (Exception ignored) {
        }
        return dto;
    }

    /**
     * 将助手回复和店铺卡片编码为持久化内容。
     *
     * @param content 助手回复内容
     * @param shops 店铺卡片列表
     * @return 编码后的持久化内容
     */
    private String encodeAssistantContent(String content, List<AgentReply.ShopCard> shops) {
        if (shops == null || shops.isEmpty()) {
            return content;
        }
        try {
            AgentReply payload = new AgentReply();
            payload.setText(content);
            payload.setShops(shops);
            return AGENT_PAYLOAD_PREFIX + objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return content;
        }
    }
}
