package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.ConversationUserDTO;
import com.hmdp.dto.MessageDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Conversation;
import com.hmdp.entity.Message;
import com.hmdp.entity.User;
import com.hmdp.mapper.MessageMapper;
import com.hmdp.service.IConversationService;
import com.hmdp.service.IMessageService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.USER_CHAT_UNREAD_KEY;

@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements IMessageService  {

    @Resource
    private IConversationService conversationService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private SimpMessagingTemplate messagingTemplate;

    @Override
    public Result sendMessage(Long toUserId, String content) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录");
        }
        Long fromUserId = user.getId();

        Conversation conversation = conversationService.getOrCreateConversation(fromUserId, toUserId);

        Message message = new Message()
                .setConversationId(conversation.getId())
                .setFromUserId(fromUserId)
                .setToUserId(toUserId)
                .setContent(content)
                .setCreateTime(LocalDateTime.now());

        save(message);

        // 更新未读消息数
        String key = USER_CHAT_UNREAD_KEY + toUserId + ":" + fromUserId;
        stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, 30, java.util.concurrent.TimeUnit.DAYS);
        // 组装推送 DTO
        MessageDTO dto = new MessageDTO();
        dto.setId(message.getId());
        dto.setContent(content);
        dto.setCreateTime(message.getCreateTime());
        dto.setFromUserId(fromUserId);
        dto.setIsSelf(false);
        User sender = userService.getById(fromUserId);
        if (sender != null) {
            dto.setFromNickname(sender.getNickName());
            dto.setFromIcon(sender.getIcon());
        }

        // 推送给对方，用 userId 作为目标
        messagingTemplate.convertAndSendToUser(
                toUserId.toString(),   // 接收方
                "/queue/messages",     // 订阅频道
                dto
        );

        return Result.ok(message.getId());
    }

    @Override
    public Result queryConversationsById(Long id) {
        //1.根据用户id查询会话列表
        List<Conversation> conversations = conversationService.queryConversationListByUserId(id);
        if(conversations.isEmpty()){
            //1.1 没有会话id列表，返回空列表
            return Result.ok();
        }
        //2.构建返回数据
        List<ConversationUserDTO> conversationUserDTOs = conversations.stream().map(conversation -> {
            ConversationUserDTO conversationUserDTO = new ConversationUserDTO();
            //设置会话ID
            conversationUserDTO.setConversationId(conversation.getId());
            //设置对方用户ID
            Long userId = conversation.getUserAId().equals(id) ? conversation.getUserBId() : conversation.getUserAId();
            conversationUserDTO.setUserId(userId);
            //设置未读消息数
            String key = USER_CHAT_UNREAD_KEY + id + ":" + userId;
            String unreadCountStr = stringRedisTemplate.opsForValue().get(key);
            conversationUserDTO.setUnreadCount(unreadCountStr != null ? Integer.parseInt(unreadCountStr) : 0);
            //设置用户信息
            User user = userService.getById(userId);
            if (user != null) {
                conversationUserDTO.setNickname(user.getNickName());
                conversationUserDTO.setIcon(user.getIcon());
            }
            return conversationUserDTO;
        }).collect(Collectors.toList());
        //返回会话用户dto列表
        return Result.ok(conversationUserDTOs);
    }


    @Override
    public Result queryMessageByConversationId(Long conversationId) {
        // 1. 获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("用户未登录");
        }
        Long currentUserId = currentUser.getId();

        // 2. 校验会话归属
        Conversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            return Result.fail("会话不存在");
        }
        boolean isMember = currentUserId.equals(conversation.getUserAId())
                        || currentUserId.equals(conversation.getUserBId());
        if (!isMember) {
            return Result.fail("无权限查看该会话");
        }

        // 3. 查询消息列表
        List<Message> messages = lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getCreateTime)
                .list();

        // 4. 组装 DTO，批量查发送者信息（避免 N+1）
        // 收集所有 fromUserId 去重
        Set<Long> userIds = messages.stream()
                .map(Message::getFromUserId)
                .collect(Collectors.toSet());
        // 批量查用户，转为 map
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<MessageDTO> result = messages.stream().map(msg -> {
            MessageDTO dto = new MessageDTO();
            dto.setId(msg.getId());
            dto.setContent(msg.getContent());
            dto.setCreateTime(msg.getCreateTime());
            dto.setFromUserId(msg.getFromUserId());
            dto.setIsSelf(msg.getFromUserId().equals(currentUserId));

            User sender = userMap.get(msg.getFromUserId());
            if (sender != null) {
                dto.setFromNickname(sender.getNickName());
                dto.setFromIcon(sender.getIcon());
            }
            return dto;
        }).collect(Collectors.toList());

        // 5. 清除未读数
        Long otherUserId = currentUserId.equals(conversation.getUserAId())
                ? conversation.getUserBId()
                : conversation.getUserAId();
        String key = USER_CHAT_UNREAD_KEY + currentUserId + ":" + otherUserId;
        stringRedisTemplate.delete(key);

        return Result.ok(result);
    }

    @Override
    public Result deleteMessageById(Long messageId) {
        // 1. 获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("用户未登录");
        }
        Long currentUserId = currentUser.getId();

        // 2. 校验消息是否存在
        Message message = getById(messageId);
        if (message == null) {
            return Result.fail("消息不存在");
        }

        // 3. 校验用户是否有权限删除该消息
        boolean isSender = currentUserId.equals(message.getFromUserId());
        boolean isReceiver = currentUserId.equals(message.getToUserId());
        if (!isSender && !isReceiver) {
            return Result.fail("无权限删除该消息");
        }

        // 4. 执行删除操作
        removeById(messageId);

        return Result.ok();
    }

}
