package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //要查询粉丝，使用粉丝查询服务接口
    @Resource
    private IFollowService followService;

    /// 显示首页分页博客
    @Override
    public Result queryHotBlog(Integer current) {
        //TODO 分页查询，就是初始页面中查询出各个blog信息：其中有点赞数显示并且如果已经点赞，需要对isLike=true判断，是的话需要高亮
        //根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryByUser(blog);
            //TODO 显示blog的时候，要根据当前登录用户是否点赞该blog，进而选择是否高亮显示，去缓存中查询blog是否点赞，在封装进入blog中isLiked字段
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /// 显示详细单个博客内容
    @Override
    public Result queryBlogById(Long id) {
        //TODO blog详细查询：其中有点赞数显示并且如果已经点赞，需要对isLike=true判断，是的话需要高亮
        //查询blog
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        //查询与blog相关的用户信息
        queryByUser(blog);
        //TODO 显示blog的时候，要根据当前登录用户是否点赞该blog，进而选择是否高亮显示，去缓存中查询blog是否点赞，在封装进入blog中isLiked字段
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /// 点赞逻辑
    @Override
    public Result likeBlog(Long id) {
        //TODO 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //TODO 2.判断当前用户是否已经点过赞：这里暴力做法直接查数据库，但是数据库负载不能太大，所以单纯的查询不修改，一定放在缓存中实现！！！！！！
        // 缓存中，使用哪种数据结构？根据元素查询/唯一/有序（因为要查询点赞按照时间排行前五的用户） : ZSET(SORTED SET)
        String key = BLOG_LIKED_KEY + id;
        //TODO ZSET中，没有isMember，我们可以查询key对应的set中，目标，是否存在score(判断ZSET是否存在某个元素的方法)
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //score == null -> 未点赞，缓存集合中查不到这个用户ID的时间戳(score)
        if(score == null) {
            //TODO 3.如果未点赞，可以点赞
            //TODO 3.1.数据库isLike字段++
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //TODO 3.2.写入缓存中（该blog对应点赞zset中加上这个用户）zadd key value score(时间戳，按照时间排序)
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            //TODO 4.如果已经点赞，不可以继续点赞
            //TODO 4.1.数据库isLike字段--
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //TODO 4.2.用户从set中移除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /// 显示点赞列表
    //TODO 实现按照点赞时间戳排序,返回点赞前五名用户的UserDTO类，单纯查询点赞从缓存中，且缓存是ZSET，按时间戳排序好的，直接查并返回就行
    //返回点赞用户list
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //TODO 查询点赞时间从前到后top5的用户ID zrange key 0 5
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //TODO 不是一定都有点赞列表的，没有人点赞返回空List就可以，可以用Collections收集元素，成为想要的目标类型
        if(top5==null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //TODO 根据ID查询出userDTO类
        //TODO top5中现在都是String表示的UserID，我先要的是long的标识，所以可以使用stream.map做类型的重新映射，使用.collect收集成想要的东西Collectors.toList()
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //TODO 因为需要使用mybatis-plus自定义语句，拼接最后的SQL语句，需要传入String的List，为了不写死，所以这里把目标List变成String，写入进去，list中各个值在String中用 ， 分开
        String idStr = StrUtil.join(",", ids);
        //TODO 根据用户ID去User表查询user类信息,使用userService，通过列表查询使用.listByIds；并转化成userDTO类（减少信息暴漏）;BeanUtil.copyProperties完成类与类的转换
        //TODO userService.listByIds(ids)这个他在查询的时候，where id in(给的id的list)，不会按照给定的ID顺序返回用户的顺序：后面必须加 order by field(id,5, 1(id列表顺序))
        //TODO 真实语句例子: where id in (5, 1) order by field(id, 5, 1(id列表顺序)):使用mybatis-plus自定义语句,先用.in解决前半段，因为没有order by field，所以用.last自己手写拼接，可以使用传String实现参数不写死
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("ORDER BY FIELD (id, " + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }


    /// 工具1：给博客内容中封装部分用户信息
    //TODO 查询与blog相关的用户信息
    private void queryByUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    /// 工具2：显示博客的两个功能中，查询登录的用户是否给这个博客点过赞，通过IsLike字段传递给前端，点过赞高亮显示
    //TODO 缓存中查询blog是否点赞
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user == null){
            //TODO 用户没登陆，不需要查询用户是否给谁点过赞
            return ;
        }
        //TODO 1.获取当前登录用户
        Long userId = user.getId();
        //TODO 2.判断当前用户是否已经点过赞：这里暴力做法直接查数据库，但是数据库负载不能太大，所以单纯的查询不修改，一定放在缓存中实现！！！！！！
        // 缓存中，使用哪种数据结构？根据元素查询/唯一/有序（因为要查询点赞按照时间排行前五的用户） : ZSET(SORTED SET)
        String key = BLOG_LIKED_KEY + blog.getId();
        //TODO ZSET中，没有isMember，我们可以查询key对应的set中，目标，是否存在score(判断ZSET是否存在某个元素的方法)
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /// 保存写好的博客到数据库，同时为了实现Feed流推模式(写完就推送所有粉丝)，需要将blogID放到收信箱中(Redis的ZSet)，由于已经存于数据库，所以发消息只需要发blogID
    //TODO FEED流：信息的主动推送，用信息匹配人，分为timeline、智能算法。我们这个业务类似微信朋友圈，使用timeline算法
    // timeline分为拉模式、推模式、推拉模式，具体看课件
    //TODO FEED流“推模式”，推动功能，写完博客马上给粉丝推送到收件箱(Redis维护的ZSET)
    @Override
    public Result saveBlog(Blog blog) {
        //TODO 写博客内容，在这里只需要加入UserID即可，其余内容前端包装好了
        //获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败！");
        }
        //TODO 查询该用户所有的粉丝(在follow中，follow_user_id对应这里的user_id)
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //TODO 将blog ID推送给所有粉丝
        for(Follow follow : follows){
            //粉丝ID：follow.getUserId()，key标识粉丝的收件箱
            String key = FEED_KEY + follow.getUserId();
            //TODO 推送:每个粉丝都有自己的Feed收件箱，ZSET结构，向ZSET中推送blog ID(blog已经存在雨数据库，交互只需要blog ID即可)
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        //返回id
        return Result.ok(blog.getId());
    }

    /// 滚动分页查询，当关注的人发了blog，由feed流推送来新的文章，通过滚动查询实现观看
    //TODO 前端请求参数使用？拼接，使用 @RequestParam("lastId") Long max 方式接收
    /*
     * TODO
     *  Feed流推送，已经在saveBlog的时候，放到了对应粉丝的Redis的ZSET结构邮箱中，我们现在要使用分页的方法，从新到旧（大到小）取出并显示。
     *  1、传统的分页方式使用页脚，但是当数据在持续更新时，页脚就会混乱，导致分页错误。
     *  此时我们就应该采用升级版：滚动查询 -> 我们不使用页脚，我们只需要记录，上一次查询的最小值；然后在下一次查询中，以这个最小值作为起点，跳过上次查询中这个最小值的数量，继续查询出相应个数即可
     *  2、首先，我们信箱时Redis做的，何种数据结构支持上面那要求。List只能支持用页脚，就没有除了页脚和数据以外的别的数据，所以不行
     *  Sorted Set不仅由排名，还可以按照分数查询，恰好满足上面要求，所以印证了之前saveBlog的做法。
     *  3、传入的参数：max是上次查询的结果中最小值，也就是这次查询中的起始最大值；offset是上次查询中这个最小值的数量，我们需要跳过这些进行查询
     *  4、返回的参数：ScrollResult类：list，表示查询出来的结果；minTime：查询的最小值，作为下一次查询的最大值；offset：查询结果中最小值数量
     *  5、在实际代码中，score存储的是时间戳；第一次的时候，minTime为前端获取的当前时间戳、offset不给，服务器默认0
     *  后续请求中，返回参数中的minTime、offset传递给前端，前端收到后，作为下一次滚动分页查询请求的参数再次传递回后端。滚动分页就这样一直持续下去。
     *  6、在Redis语句中，共4个参数需要注意，两个写死，两个传递
     *  max     当前时间戳（第一次） | 上一次查询的最小时间戳
     *  min     0（不关心，写死）
     *  offset  0（第一次）        | 上一次结果中，与最小值一样的元素数量
     *  count   2（与前端商量好，写死）
     * */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户并查找收件箱（滚动分页） ZREVRANGEBYSCORE key max min WITHSCORES LIMIT offset count
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //TODO 查出东西一定要非空判定，否则空指针
        if(typedTuples==null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //TODO 解析数据：blogId/minTime/Offset
        List<Long> ids = new ArrayList<>(typedTuples.size());//TODO 用List保存blog ID，用于后面查询所有blog信息
        long minTime = 0;//TODO 动态维护此次查询的最小时间戳
        int os = 1;//TODO 动态维护此次查询最小时间戳出现次数
        for(ZSetOperations.TypedTuple<String> tuple : typedTuples){
            //获取Blog ID
            ids.add(Long.valueOf(tuple.getValue()));
            //TODO 动态维护最小时间戳 + 最小时间戳出现次数，因为我们的数据结构保证了一定严格降序排列所有数据，所以可以这么维护
            long time = tuple.getScore().longValue();
            if(time == minTime){//是最小时间，计数++
                os++;
            } else{
                minTime = time;//新的时间戳肯定更小，更新最小时间+最小时间计数
                os = 1;
            }
        }
        //TODO 根据blogId查询所有的blog，要保证按照ids给的顺序，有序从数据库查询，listByIds做不到，使用order by field
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD (id, " + idStr + ")").list();
        for(Blog blog : blogs){
            //查询与blog相关的用户信息
            queryByUser(blog);
            //根据当前登录用户是否点赞该blog，进而选择是否高亮显示(去缓存中查询blog是否点赞，在封装进入blog中isLiked字段)
            isBlogLiked(blog);
        }
        //封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

}
