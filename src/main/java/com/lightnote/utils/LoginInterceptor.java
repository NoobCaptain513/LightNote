package com.lightnote.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.lightnote.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.lightnote.utils.RedisConstants.LOGIN_USER_KEY;
import static com.lightnote.utils.RedisConstants.LOGIN_USER_TTL;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            //没有，设置拦截状态码
            response.setStatus(401);
            System.out.println("未登录");
            return false;
        }
        //有用户，则放行
        return true;
    }

}
