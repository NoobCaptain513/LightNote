package com.lightnote.controller;


import com.lightnote.dto.Result;
import com.lightnote.entity.ShopType;
import com.lightnote.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("/list")
    public Result queryTypeList() {
        List<ShopType> typeList = typeService.queryTypeList();
        return Result.ok(typeList);
    }
}
