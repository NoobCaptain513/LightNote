package com.lightnote.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lightnote.dto.Result;
import com.lightnote.dto.UserDTO;
import com.lightnote.entity.Blog;
import com.lightnote.entity.Follow;
import com.lightnote.entity.User;
import com.lightnote.mapper.FollowMapper;
import com.lightnote.service.IBlogService;
import com.lightnote.service.IFollowService;
import com.lightnote.utils.UserHolder;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import cn.hutool.core.util.StrUtil;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserServiceImpl userService;

    @Resource
    @Lazy
    private IBlogService blogService;

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = Math.toIntExact(query().eq("user_id", userId).eq("follow_user_id", followUserId).count());
        return Result.ok(count > 0);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        if (isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess){
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());

                String feedKey = "feed:" + userId;
                List<Blog> existingBlogs = blogService.query()
                        .eq("user_id", followUserId)
                        .list();
                for (Blog blog : existingBlogs) {
                    long timestamp = blog.getCreateTime()
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli();
                    stringRedisTemplate.opsForZSet()
                            .add(feedKey, blog.getId().toString(), timestamp);
                }
            }
        } else {
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        String key2 = "follow:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list()
                .stream()
                .map(this::toUserDTO)
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    private UserDTO toUserDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setNickName(user.getNickName());
        dto.setIcon(user.getIcon());
        return dto;
    }
}
