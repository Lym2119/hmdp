package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
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
    //使用了mybatisPlus的工具，目标：将此服务更改为缓存处理，因为商铺类型很少发生变化，适合缓存

    //控制器中不负责具体的服务实现，实现结果也要回到控制器中，最后回到前端
    // 要使用@Resource将服务接口给自动注入,并定义服务方法
    @Resource
    private IShopTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
//        List<ShopType> typeList = typeService
//                .query().orderByAsc("sort").list();
//        return Result.ok(typeList);
        return typeService.queryType();
    }
}
