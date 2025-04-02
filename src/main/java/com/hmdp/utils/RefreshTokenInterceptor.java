package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

//TODO 模仿session：验证一次，一个请求，刷新token生存周期
//TODO 问题：不是所有路径都走登录拦截器，这样会导致访问了浏览器操作，但不能刷新Redis
//TODO 解决：再加一个拦截一切的拦截器，只为了刷新Redis，以及获取用户，不管用户存不存在，后续因为没登陆而拦截依靠这个拦截器
//TODO 所以不同功能，也要对拦截器进行分开书写，需要确认用户登录的，再走这个拦截器

/*
 * Token刷新拦截器：
 * 任何请求路径都要进行token刷新
 *  所以：建立拦截器（工具类），先拦截再分发，如果有需要登陆验证的再走登录拦截器
 *  由于后续操作需要用户信息，且要线程安全，所以拦截验证之后
 *  用户信息存入threadLocal中，即每个请求一个线程，存到thread自己的空间中，一人一个
 *
 * */

/*
 *
 * 需要实现 HandlerInterceptor接口：三个方法
 * preHandle:控制器前拦截
 * postHandle:控制器后拦截
 * afterCompletion：返回给用户前
 *
 * */


/*
 *
 * 1、书写拦截器代码
 * 2、去com.hmdp.config.MvcConfig文件配置拦截器生效
 * 3、配置拦截路径,排除不需要的
 *
 * */

public class RefreshTokenInterceptor implements HandlerInterceptor{
    /**
     * 因为业务要改为Redis实现，所以在拦截其中，我们要操作redis，就需要stringRedisTemplate
     * 但是！！！LoginInterceptor并没有加注解@compent这些注解，也就是说，这个类
     * 不是Spring负责管理的，是我们自己创造的，就不可以直接使用，@Resources @Autowired直接注入
     * 我们就需要使用这个类的构造函数，来自己对就需要stringRedisTemplate，实现一个注入
     * 拦截器不要使用@compent注解！！！！！！
     * */

    /**
     * 我们使用构造函数手动注入，就要看谁用了构造函数
     * 我们在com.hmdp.config.MvcConfig中使用这个拦截器，那个类是归Spring管理
     * 所以我们只需要在com.hmdp.config.MvcConfig中自动注入stringRedisTemplate
     * 在传递回来，就可以达成目的！
     * */

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取session
        //HttpSession session = request.getSession();

        //TODO 从前端请求中，获取token，方法见common.js
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)) {
            //如果是空串，不做处理，只是不去获取用户数据，直接放行，有登录需要交给登录拦截器
            return true;
        }

        //TODO 现在token正确，我需要从Redis获取用户信息,存的是HashMap
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        if(userMap.isEmpty()){
            //如果没有用户，不做处理，只是不去获取用户数据，直接放行，有登录需要交给登录拦截器
            return true;
        }

        //获取用户信息:
        //第一信息太多容易泄露、第二信息太多对服务器压力大
        //所以登录存减配数据对象Use人DTO，这里也取这个
        //TODO 取的是HashMap，需要在转化成UserDTO类才能支持后续使用，后续村的都是UserDTO类
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //成功登录后，存用户信息到ThreadLocal，使用工具类UserHolder
        UserHolder.saveUser(userDTO);

        //TODO 模仿session：验证一次，一个请求，刷新token生存周期
        //TODO 问题：不是所有路径都走登录拦截器，这样会导致访问了浏览器操作，但不能刷新Redis
        //TODO 应该是有任何请求都应该刷新Redis
        //TODO 解决：再加一个拦截一切的拦截器，只为了刷新Redis，以及获取用户，不管用户存不存在，后续因为没登陆而拦截依靠这个拦截器
        //TODO 所以不同功能，也要对拦截器进行分开书写，需要确认用户登录的，再走这个拦截器
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //放行
        return true;
    }

    //执行完毕，销毁信息,避免泄露
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //后台操作都完成，发给前端前，把数据销毁，防止内存、信息泄露
        UserHolder.removeUser();
    }
}
