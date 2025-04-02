package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 *
 * 方法中id：博客的id
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    //TODO 控制器层不要有这么多实现，实现要放在服务层

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /// 博客的点赞功能：一个用户只能点赞一次！！！！！！！！
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 修改点赞数量
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /// 点击博客详情页面中的作者信息，下面分页显示作者全部的Blog
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        //TODO 根据用户ID分页查询：获取全部分页
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        //TODO 获取当前分页
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /// 实现软件首页分页查询博客大概内容
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /// 实现查询博客具体内容方法,博客内容 + 发布者的部分信息（不可以暴漏给前端太多）
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /// 实现按照点赞时间戳排序,返回点赞前五名用户的UserDTO类，单纯查询点赞从缓存中，且缓存是ZSET，按时间戳排序好的，直接查并返回就行
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /// 滚动分页查询，当关注的人发了blog，由feed流推送来新的文章，通过滚动查询实现观看
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        //TODO 前端请求参数使用？拼接，使用 @RequestParam("lastId") Long max 方式接收
        /*
        * TODO Feed流推送，已经在saveBlog的时候，放到了对应粉丝的Redis的ZSET结构邮箱中，我们现在要使用分页的方法，从新到旧（大到小）取出并显示。
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
        return blogService.queryBlogOfFollow(max, offset);
    }
}
