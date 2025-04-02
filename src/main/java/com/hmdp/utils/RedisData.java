package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

//TODO 这个类是为了解决：防止缓存击穿，采用设置逻辑过期时间
//TODO 但是原对象Shop并没有逻辑过期时间对象：所以为了不修改已有的业务，我们单独创建一个工具类
//TODO 它既有逻辑过期时间 + 加上原本数据
@Data
public class RedisData {
    //可以选择继承Shop && 也可以这样，放一个万能对象存储器
    private LocalDateTime expireTime;
    private Object data;//一个万能对象存储器
}
