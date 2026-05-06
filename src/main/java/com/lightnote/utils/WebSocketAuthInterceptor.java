package com.lightnote.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.lightnote.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

import static com.lightnote.utils.RedisConstants.LOGIN_USER_KEY;

@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        System.out.println("WebSocket handshake started");
        if (request instanceof ServletServerHttpRequest) {
            HttpServletRequest req = ((ServletServerHttpRequest) request).getServletRequest();
            System.out.println("Request URL: " + req.getRequestURL());
            // 从 URL 参数取 token（和你现有登录体系一致）
            String token = req.getParameter("token");
            System.out.println("Token: " + token);
            if (StrUtil.isBlank(token)) {
                System.out.println("Token is blank");
                return false;
            }

            // 从 Redis 中获取用户信息
            String key = LOGIN_USER_KEY + token;
            System.out.println("Redis key: " + key);
            Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
            System.out.println("User map size: " + userMap.size());
            if (userMap.isEmpty()) {
                System.out.println("User map is empty");
                return false;
            }

            // 将查询到的hash数据转为UserDTO
            UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
            System.out.println("User ID: " + userDTO.getId());
            attributes.put("userId", userDTO.getId().toString());
        }
        System.out.println("WebSocket handshake completed successfully");
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception ex) {}
}