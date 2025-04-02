package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

/*
* 商户类型：这类型数据属于是极难变化，一致性需求很低，所以对于缓存一致性而言
* 之需要使用Redis自带的内存回收机制就可以保证，过一段时间删掉这个缓存，保证一致性
* 也可以加上超时兜底作为备用
* */

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryType() {
        //定义redis缓存Key,并从redis中获取缓存
        String typeJsonString = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        //在缓存中查询到商铺类型列表，直接返回
        //Json串转List用JSONUtil.toList
        if (StrUtil.isNotBlank(typeJsonString)) {
            return Result.ok(JSONUtil.toList(typeJsonString, ShopType.class));
        }

        //如果缓存未命中，没有查询到商铺类型列表，去数据库获取
        List<ShopType> typeList = query().orderByAsc("sort").list();

        //未读取到商铺类型信息，返回404
        if(typeList.isEmpty()) {
            return Result.fail("没有查询到商品类型信息");
        }

        //更新缓存:JSONUtil.toJsonStr可以把一切对象转化为Json字符串
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList));

        //返回结果
        return Result.ok(typeList);
    }
}
