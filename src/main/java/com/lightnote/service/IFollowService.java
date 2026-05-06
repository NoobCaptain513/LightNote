package com.lightnote.service;

import com.lightnote.dto.Result;
import com.lightnote.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result isFollow(Long followUserId);

    Result follow(Long followUserId, Boolean isFollow);

    Result followCommons(Long id);
}
