package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;//接口注入，不要注入实现类

    //TODO 新业务：共同关注，使用redis中set结构可以很简单的实现交集查询，每个用户维护自己的关注列表，最后redis中求交集
    // 所以我们要在维护数据库同时维护redis
    //TODO 关注、取关操作
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //关注取关的逻辑很简单，就是往数据库加/删 登录用户ID+关注的用户ID即可
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        if(isFollow){
            //关注:存一下followUserId 和 userId
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean success = save(follow);
            if(success){/// 数据库先操作成功，再维护缓存
                //TODO 关注成功，Redis缓存中开始维护
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else{
            //TODO 取消关注:把对应followUserId 和 userId的表项删了，使用mybatis-plus特殊语法new QueryWrapper<Follow>()，后面接where条件语句
            // delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("follow_user_id", followUserId).eq("user_id", userId));
            if(success){/// 数据库先操作成功，再维护缓存
                //TODO 取消关注成功，Redis缓存中去掉followUserId
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    //TODO 判断当前登录用户是否关注了这个blog作者,在blog详情页要针对这里返回值改显示内容
    @Override
    public Result isFollow(Long followUserId) {
        //TODO 已经在缓存中维护当前用户关注set，直接访问缓存
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, followUserId.toString());

/*        //TODO 查询是否关注，我只需要查询这俩人ID在表单中是否对应一个表项，个数即可,个数>0证明有关注记录，已经关注
        // select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count = query().eq("follow_user_id", followUserId).eq("user_id", userId).count();*/

        return Result.ok(BooleanUtil.isTrue(isMember));
    }

    /// 求共同关注，参数表示的是：目标用户，当前登录用户在UserHolder
    @Override
    public Result followCommons(Long id) {
        //TODO 已经在缓存中维护了两个个用户关注列表set，只需要求交集(需要两个Key)就行
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //TODO将得到的交集用户ID转换成Long，并使用IUserService服务查询到用户信息(无顺序要求)，转化成UserDTO返回给前端
        if(intersect == null || intersect.isEmpty()){
            //TODO 没交集
            return Result.ok();
        }
        //TODO 有交集
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
