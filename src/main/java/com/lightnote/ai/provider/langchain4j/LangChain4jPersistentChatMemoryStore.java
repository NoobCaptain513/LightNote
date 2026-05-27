package com.lightnote.ai.provider.langchain4j;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lightnote.entity.AiMessage;
import com.lightnote.mapper.AiMessageMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.JacksonChatMessageJsonCodec;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class LangChain4jPersistentChatMemoryStore implements ChatMemoryStore {

    private static final String MEMORY_ID_SEPARATOR = ":";
    private static final String CHAT_ROLE = "lc4j_chat";
    private static final String AGENT_ROLE = "lc4j_agent";

    private final AiMessageMapper aiMessageMapper;
    private final JacksonChatMessageJsonCodec codec = new JacksonChatMessageJsonCodec();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        MemoryKey key = parseMemoryId(memoryId);
        LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiMessage::getUserId, key.userId())
                .eq(AiMessage::getRole, key.role())
                .orderByDesc(AiMessage::getCreateTime)
                .last("limit 1");

        AiMessage message = aiMessageMapper.selectOne(wrapper);
        if (message == null || message.getContent() == null || message.getContent().trim().isEmpty()) {
            return List.of();
        }

        try {
            return codec.messagesFromJson(message.getContent());
        } catch (Exception e) {
            log.warn("LangChain4j记忆反序列化失败，memoryId={}", memoryId, e);
            return List.of();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        MemoryKey key = parseMemoryId(memoryId);
        deleteByKey(key);
        aiMessageMapper.insert(new AiMessage()
                .setUserId(key.userId())
                .setRole(key.role())
                .setContent(codec.messagesToJson(messages == null ? List.of() : messages))
                .setCreateTime(LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMessages(Object memoryId) {
        deleteByKey(parseMemoryId(memoryId));
    }

    private void deleteByKey(MemoryKey key) {
        LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiMessage::getUserId, key.userId())
                .eq(AiMessage::getRole, key.role());
        aiMessageMapper.delete(wrapper);
    }

    private MemoryKey parseMemoryId(Object memoryId) {
        String value = String.valueOf(memoryId);
        int splitIndex = value.lastIndexOf(MEMORY_ID_SEPARATOR);
        if (splitIndex < 0 || splitIndex == value.length() - 1) {
            throw new IllegalArgumentException("LangChain4j memoryId格式错误: " + value);
        }

        String scope = value.substring(0, splitIndex);
        Long userId = Long.valueOf(value.substring(splitIndex + 1));
        String role = scope.contains("agent") ? AGENT_ROLE : CHAT_ROLE;
        return new MemoryKey(userId, role);
    }

    private record MemoryKey(Long userId, String role) {
    }
}
