package com.hmdp;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.controller.UserController;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;

@SpringBootTest
@AutoConfigureMockMvc
public class LoginTest {
    @Resource
    private UserServiceImpl userService;

    @Resource
    private UserController userController;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void Login() throws Exception {
        List<String> tokenList = new ArrayList<>();
        List<String> validTokenList = new ArrayList<>(); // 只保存在Redis中验证有效的token
        List<User> userList = userService.query().list();

        for (User user : userList) {
            String phone = user.getPhone();

            // 发送验证码
            Result sendCodeResult = userService.sendCode(phone);
            if (sendCodeResult.getSuccess()) {
                Thread.sleep(50); // 增加等待时间

                // 获取验证码
                String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
                if (code == null) {
                    System.out.println("用户 " + phone + " 的验证码未找到");
                    continue;
                }

                // 创建登录表单
                LoginFormDTO loginFormDTO = new LoginFormDTO();
                loginFormDTO.setCode(code);
                loginFormDTO.setPhone(phone);

                // 执行登录请求
                MvcResult mvcResult = mockMvc.perform(MockMvcRequestBuilders.post("/user/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(loginFormDTO)))
                        .andExpect(MockMvcResultMatchers.status().isOk())
                        .andReturn();

                // 解析响应内容获取token
                String responseContent = mvcResult.getResponse().getContentAsString();
                Result result = objectMapper.readValue(responseContent, Result.class);

                if (result.getSuccess() && result.getData() != null) {
                    String token = result.getData().toString();
                    String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

                    // 验证token是否真的在Redis中创建
                    Boolean hasTokenInRedis = stringRedisTemplate.hasKey(tokenKey);
                    if (Boolean.TRUE.equals(hasTokenInRedis)) {
                        // 设置authorization响应头
                        mvcResult.getResponse().setHeader("authorization", token);
                        System.out.println("成功获取并验证token:" + token);
                        validTokenList.add(token);
                    } else {
                        System.out.println("警告: 用户 " + phone + " 登录成功但token未在Redis中找到");
                    }
                } else {
                    System.out.println("用户 " + phone + " 登录失败: " + result.getErrorMsg());
                }
            } else {
                System.out.println("用户 " + phone + " 发送验证码失败");
            }

            Thread.sleep(50);
        }

        // 确保Token目录存在
        FileUtil.mkdir("D:/Token");
        // 将获取到的token保存到一个文件中（一行一个Token）
        StringBuilder sb = new StringBuilder();
        for (String token : validTokenList) {
            sb.append(token).append("\n");
        }
        // 写入文件（覆盖模式）
        FileUtil.writeString(sb.toString(), "D:/Token/token.txt", StandardCharsets.UTF_8);

        System.out.println("共获取到 " + validTokenList.size() + " 个有效token，已保存到 D:/Token/token.txt");

    }


    //查看数据库中有多少不同的手机号
    @Test
    public void queryPhone(){
        List<User> userList = userService.query().list();
        HashSet<String> phoneList = new HashSet<>();
        for(User user : userList){
            phoneList.add(user.getPhone());
        }
        //数组大小
        System.out.println(phoneList.size()+ "不同电话号码");
    }

}
