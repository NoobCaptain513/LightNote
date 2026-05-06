package com.lightnote.service;

import com.baomidou.mybatisplus.core.conditions.interfaces.Func;
import com.lightnote.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *

 */
public interface IShopTypeService extends IService<ShopType> {

    List<ShopType> queryTypeList();
}
