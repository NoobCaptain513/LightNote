package com.lightnote.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration  // 必须加这个注解
public class RedisConfig {

    // 就是加这里！
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);

        // 解决你 一次成功一次400 的关键代码
        template.setEnableTransactionSupport(false);

        return template;
    }
}