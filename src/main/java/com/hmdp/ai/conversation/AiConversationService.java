package com.hmdp.ai.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.AiMessageDTO;
import com.hmdp.dto.AgentReply;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.AiMessage;
import com.hmdp.mapper.AiMessageMapper;
import com.hmdp.utils.UserHolder;
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

    public Long getCurrentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }

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

    public Result getHistory(Long userId, int maxHistoryMessages) {
        if (userId == null) {
            return Result.fail("用户未登录");
        }
        return Result.ok(getHistoryMessages(userId, maxHistoryMessages));
    }

    public List<AiMessageDTO> getHistoryMessages(Long userId, int maxHistoryMessages) {
        LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiMessage::getUserId, userId)
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

    public List<AiMessageDTO> historyWithoutLastUser(List<AiMessageDTO> recentMessages) {
        if (recentMessages == null || recentMessages.size() <= 1) {
            return new ArrayList<>();
        }
        return new ArrayList<>(recentMessages.subList(0, recentMessages.size() - 1));
    }

    public AiMessageDTO getLastMessage(List<AiMessageDTO> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return null;
        }
        return recentMessages.get(recentMessages.size() - 1);
    }

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

    public void saveAssistantMessage(Long userId, String content) {
        saveAssistantMessage(userId, content, null);
    }

    public void saveAssistantMessage(Long userId, String content, List<AgentReply.ShopCard> shops) {
        aiMessageMapper.insert(new AiMessage()
                .setUserId(userId)
                .setRole("assistant")
                .setContent(encodeAssistantContent(content, shops))
                .setCreateTime(LocalDateTime.now()));
    }

    public String safeAssistantReply(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "我已经查到一些结果，但当前总结为空，你可以换一种问法继续问我。";
        }
        return content.trim();
    }

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
