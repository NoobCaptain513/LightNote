package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.interfaces.Func;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;


import jakarta.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryTypeList() {
        //1.查询reids缓存
        String key = CACHE_SHOP_TYPE_KEY;
        String shopType = stringRedisTemplate.opsForValue().get(key);


        //2.判断是否存在
        if (StrUtil.isNotBlank(shopType)) {

            //3.存在直接返回
            return JSONUtil.toList(shopType, ShopType.class);
        }

        //4.不存在，查询数据库
        List<ShopType> typeList = list();
        System.out.println(typeList);


        //5.数据库不存在，返回空
        if (typeList == null) {
            return null;
        }
        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        return typeList;
    }
}
