package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 *
 * 后端项目的入口！
 *
 * 前端像后端，发送的是：请求路径+请求类型+参数
 * 后端与前端相对接的：控制器类，并根据其路径，进行分流@RequestMapping("/user")
 * 根据路径分到相应的类和方法，前端参数作为方法参数进来
 * 控制器将服务请求发到服务接口
 * 服务接口定义方法，再由相应的服务类去实现它
 * 服务类实现时，用到的自定义工具，需要放置到Utils下
 *
 */
@Slf4j
@RestController
@RequestMapping("/user")//路径解析，路径第一个/api不用管，后面按照/user进到这个处理类
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")//路径解析，POST方法，再根据路径中/code进入这个接受前端发送验证码服务的处理方法中
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // TODO 发送短信验证码并保存验证码
        // TODO Controller 层实现的是 接收前端发来服务请求，真正实现要放到service层
        // 前端回来一个String 用@RequestParam("phone") 注解接收，（）内放置用于标识请求内不同字段的字符串
        return userService.sendCode(phone,session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // TODO 实现登录功能
        // {phone: "15754340989", code: "111111"}前端回来一个Json，后端用 @RequestBody注解，并放到一个实体类进行接收
        // 登陆成功 + 验证码校验都要用session
        return userService.login(loginForm, session);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(){
        // TODO 实现登出功能
        return Result.fail("功能未完成");
    }

    /**
     * 登录校验：成功登录后，需要校验登陆状态
     *  任何操作之前，都要校验
     *  如果任何控制器都来实现这个操作：臃肿麻烦，线程不安全
     *  所以：建立拦截器（工具类），先拦截再分发
     *  由于后续操作需要用户信息，且要线程安全，所以拦截验证之后
     *  用户信息存入threadLocal中，即每个请求一个线程，存到thread自己的空间中，一人一个
     *
     *  从拦截器出来，再到/user/me路径，完成操作
     */

    @GetMapping("/me")
    public Result me(){
        // TODO 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    /// 点开blog中用户页面，展示blog作者信息详情
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        //查询作者信息,因为前后端流通，使用userDTO
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    /// 实现用户签到，没有任何参数+返回值，使用Redis的Bitmap结构
    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    /// 实现:当前用户截止到今天的，在本月的连续签到天数（从最后一次签到往前数，直到全部遍历或者遇到未签到）
    /// 没有传递进来的参数，返回连续签到天数
    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }
}
