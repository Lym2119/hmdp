package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //TODO 让已有的拦截器生效，且通过设置 order()设置顺序，越大优先级越低
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //使用拦截器注册工具直接注册且定义拦截白名单
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(//"/blog/hot",
                        "/user/login",
                        "/user/code",
                        "/upload/**",
                        "/blog/hot",
                        "/shop-type/**",
                        "/voucher/**",
                        "/shop/**"
                ).order(1);

        //TODO 拦截所有，且先执行，要设置执行顺序
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }
}
