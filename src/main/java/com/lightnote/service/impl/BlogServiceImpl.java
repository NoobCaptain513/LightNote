package com.lightnote.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lightnote.dto.Result;
import com.lightnote.dto.ScrollResult;
import com.lightnote.dto.UserDTO;
import com.lightnote.entity.Blog;
import com.lightnote.entity.Follow;
import com.lightnote.entity.User;
import com.lightnote.mapper.BlogMapper;
import com.lightnote.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lightnote.service.IFollowService;
import com.lightnote.service.IUserService;
import com.lightnote.utils.SystemConstants;
import com.lightnote.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.lightnote.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        // 查询blog
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 查询blog有关的用户
        queryBlogUser(blog);
        // 查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1. 获取用户
        UserDTO user = UserHolder.getUser();
        if (user == null){
            // 用户未登录,无需查询
            return;
        }
        Long userId = user.getId();
        //2，判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key , userId.toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });

        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //1. 获取用户
        Long userId = UserHolder.getUser().getId();
        //2，判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //3.如果为点赞可以点赞
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1")
                    .eq("id", id).update();
            //3.2 保存用户点赞信息
            if(isSuccess){
                stringRedisTemplate.opsForZSet()
                        .add(key, userId.toString(), System.currentTimeMillis() );
            }
        }else {
            //4.如果已经点赞，则取消点赞
            //4.1数据库点数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2 把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //1.查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4 );
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //3.根据用户id查询用户
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败！");
        }
        //3.查询笔记作者的所有粉丝
        List<Follow> follows = followService.query()
                .eq("follow_user_id", user.getId()).list();
        //4.推送笔记
        for (Follow follow : follows) {
            //4.1 获取粉丝id
            Long userId = follow.getUserId();
            //4.2 推送
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }

        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "feed:" + userId;
        //2.查询收件箱，不分页，返回所有
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max);
        //3.解析数据
        if (typedTuples == null || typedTuples.isEmpty()) {
            ScrollResult emptyResult = new ScrollResult();
            emptyResult.setList(new ArrayList<>());
            emptyResult.setOffset(0);
            emptyResult.setMinTime(0L);
            return Result.ok(emptyResult);
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 0;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples){
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }

        //4.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                 .last("ORDER BY FIELD(id," + idStr + ")").list();
        blogs.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        //5.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
