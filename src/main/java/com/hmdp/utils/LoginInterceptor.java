package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/*
* 登录拦截器：
* 一些操作之前，都要校验登陆状态
*  如果任何控制器都来实现这个操作：臃肿麻烦，线程不安全
*  所以：建立拦截器（工具类），先拦截再分发
* 从ThreadLocal中拿到数据即可，因为前面已经刷新token并存入数据
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

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //TODO 因为多了一个前面刷新Redis拦截器已经把刷新了Redis，并且获取了用户数据，所以这里只要看用户数据存不存在即可，简化了逻辑，并且补全功能

        //从前端请求中，获取token，方法见common.js
//        String token = request.getHeader("authorization");
//        if(StrUtil.isBlank(token)) {
//            //如果是空串，即token不存在，直接说明没登陆，拦截
//            response.setStatus(401);
//            return false;
//        }
        
        //现在token正确，我需要从Redis获取用户信息,存的是HashMap
//        String key = RedisConstants.LOGIN_USER_KEY + token;
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        //判断这个用户是否存在，只要登陆成功他就会存在，非空？
//        if(userMap.isEmpty()){
//            response.setStatus(401);
//            return false;
//        }

        //获取用户信息:
        //第一信息太多容易泄露、第二信息太多对服务器压力大
        //所以登录存减配数据对象Use人DTO，这里也取这个
        //取的是HashMap，需要在转化成UserDTO类才能支持后续使用，后续村的都是UserDTO类
        //UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //成功登录后，存用户信息到ThreadLocal，使用工具类UserHolder
        //UserHolder.saveUser(userDTO);

        //TODO 模仿session：验证一次，一个请求，刷新token生存周期
        //TODO 问题：不是所有路径都走登录拦截器，这样会导致访问了浏览器操作，但不能刷新Redis
        //TODO 应该是有任何请求都应该刷新Redis
        //TODO 解决：再加一个拦截一切的拦截器，只为了刷新Redis，以及获取用户，不管用户存不存在，后续因为没登陆而拦截依靠这个拦截器
        //TODO 所以不同功能，也要对拦截器进行分开书写，需要确认用户登录的，再走这个拦截器
        //stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //TODO 判断有没有用户存在
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }
        //TODO 由用户，放行
        return true;
    }
}
