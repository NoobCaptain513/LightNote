package com.lightnote.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lightnote.dto.LoginFormDTO;
import com.lightnote.dto.Result;
import com.lightnote.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);

    Result sign();

    Result signCount();
}
