package com.lightnote.controller;

import cn.hutool.core.bean.BeanUtil;
import com.lightnote.dto.LoginFormDTO;
import com.lightnote.dto.Result;
import com.lightnote.dto.UserDTO;
import com.lightnote.entity.Blog;
import com.lightnote.entity.User;
import com.lightnote.entity.UserInfo;
import com.lightnote.service.IBlogService;
import com.lightnote.service.IFollowService;
import com.lightnote.service.IUserInfoService;
import com.lightnote.service.IUserService;
import com.lightnote.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private IFollowService followService;

    @Resource
    private IBlogService blogService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm){
        return userService.login(loginForm);
    }

    /**
     * 登出功能
     */
    @PostMapping("/logout")
    public Result logout(){
        return Result.fail("功能未完成");
    }

    @GetMapping("/me")
    public Result me(){
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            if (user.getNickName() == null && user.getNickname() == null && user.getId() != null) {
                User dbUser = userService.getById(user.getId());
                if (dbUser != null) {
                    user = toUserDTO(dbUser);
                }
            }
            normalizeUserDTO(user);
        }
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            info = new UserInfo();
            info.setUserId(userId);
        }
        int following = Math.toIntExact(followService.query().eq("user_id", userId).count());
        int followers = Math.toIntExact(followService.query().eq("follow_user_id", userId).count());
        List<Blog> blogs = blogService.query().eq("user_id", userId).list();
        int liked = blogs.stream()
                .map(Blog::getLiked)
                .filter(likeCount -> likeCount != null)
                .mapToInt(Integer::intValue)
                .sum();

        info.setFollowee(following);
        info.setFans(followers);
        info.setFollowing(following);
        info.setFollowers(followers);
        info.setLiked(liked);
        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        return Result.ok(toUserDTO(user));
    }

    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }

    private UserDTO toUserDTO(User user) {
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        userDTO.setNickName(user.getNickName());
        userDTO.setNickname(user.getNickName());
        return userDTO;
    }

    private void normalizeUserDTO(UserDTO userDTO) {
        if (userDTO.getNickName() == null) {
            userDTO.setNickName(userDTO.getNickname());
        }
        if (userDTO.getNickname() == null) {
            userDTO.setNickname(userDTO.getNickName());
        }
    }
}
