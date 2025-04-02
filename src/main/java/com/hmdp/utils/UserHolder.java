package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

//确认用户登陆状态拦截器中，存在用户，则将信息存到该线程的ThreadLocal中
//这个工具类实现它,工具类，全是静态方法
public class UserHolder {
    //定义一个，专门处理User
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    //存取删
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
