package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
* 以下皆为简单的Redis分布式锁的原理和实现
 * 有如下问题：不可重入（单个线程只能获得一次，如果方法嵌套使用会死锁）、非阻塞、过期时间、主从不一致等问题
 * 企业级开发时，直接使用Redisson，一个提供在JAVA中将分布式场景下的Redis使用，各种可用的组件，包括分布式锁
* */


//TODO synchronized仅适合单机模式，集群模式又会出现很多问题
/*
 * TODO 如下的解决方案原理：在同一个tomcat的JVM中，synchronized产生一个锁监视器对象，监视所有线程，加锁就是在监视器中记录线程名称，别的线程申请锁因为监视器已经有线程进入而申请失败
 *  一旦当业务部署到多个tomcat的集群时，每个tomcat有自己的JVM，每个JVM有自己的锁监视器，所以当前端请求通过nginx反向代理到n个tomcat中，会有n个线程同时获取锁
 *  当每个线程都是由一个用户发的，那么还是解决不了线程安全，还是出现了一个用户买了多个秒杀券的问题
 *  解决：Redis开发一个跨进程/JVM的全局锁（Redis实现分布式锁）
 * */
/*
 * TODO 分布式锁：满足分布式/集群模式下”多进程可见且互斥“的锁（锁监视器） + 高可用 + 高性能 + 安全性
 *  Redis利用setnx机制，获取锁，并且一定要添加过期时间（防止服务崩溃而死锁）；释放锁：手动(DEL key)+ 自动过期
 *  为了防止在setnx和设置过期时间之间宕机造成死锁：让二者保持原子性：SET lock threadID EX 10 NX(二者合二为一)
 *  获取锁分为阻塞式（失败了继续等，性能下降实现困难） + 非阻塞式（失败了结束），我们实现： 非阻塞
 * */

public class SimpleRedisLock implements ILock{

    //用构造函数，因为这个类Spring不管，需要用户用构造函数显示注入
    private StringRedisTemplate stringRedisTemplate;
    //TODO 应该用不同业务的业务名当作锁的Key，不同业务不同锁,用户注入
    private String name;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //为了更专业，给Key加总体前缀
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";//标识线程ID的前缀（JVM标识）

    //TODO timeoutSec：值看具体业务，业务执行时间的最大值即可
    @Override
    public boolean tryLock(long timeoutSec) {
        //TODO 获取Thread ID：我们需要ThreadID去辅助去掉锁，但是Thread.currentThread().getId()是在同一个JVM中递增维护
        //TODO 不同JVM中维护的不一致，所以 （UUID）标识JVM + Thread.currentThread().getId()，唯一标识集群中任意一个Thread ID
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        //TODO 获取锁：SET lock threadID EX 10 NX，存的值要模仿JVM中锁监视器，存储Thread ID（因为后续业务需要判断是不是自己的ID，所以必须加，且保证分布式情况下的唯一性）
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        //TODO 此处直接返回会触发拆箱，拆箱一定要防止空指针，可以用BooleanUtils.isTrue()
        return Boolean.TRUE.equals(success);
    }

    //TODO 这里有个极端情况：当判断完即将执行的时候，可能由于JVM的垃圾清理机制问题，给阻塞了，结果还会出现误删
    //TODO 解决：判断+删除：必须满足原子性！ -> LUA 脚本：LUA调用Redis，Redis提供该脚本，利用LUA，一次性执行所有的Redis语句，保证原子性
    //TODO Redis的LUA脚本内容：利用redis.call('命令','key','参数',...)：redis.call('set','name','Jack',...)
    //TODO Redis使用EVAL调用LUA脚本：EVAL “脚本内容” key类型参数数量 key参数(KEYS数组中) 其他参数(ARGV数组中) -> EVAL "return redis.call('set',KEYS[1],ARGV[1])" 1 name Rose (LUA中数组从1开始)
    //TODO 下一步，把这个函数逻辑，改造成LUA脚本，并用JAVA调用执行，这样就使用LUA保证了Redis的原子性，脚本内的代码Redis一块执行

    //TODO 锁的LUA文件需要载入，一定是提前载入好，每次直接用：使用RedisScript接口的DefaultRedisScript<返回值类型>实现类
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        //静态代码块初始化静态值
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //TODO 指定文件位置，使用ClassPathResource,让程序去ClassPath下的Resource文件找，()内直接指定名称即可
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /*
    * TODO 在一些高并发场景下，有很多极端场景，尽量保证对任何数据库的操作都是原子性的
    *  比如这个业务，不使用LUA即Redis原子性操作的话，就因为先判断后删除锁就被钻空子了
    *  所以对库的操作都要原子性
    * */

    @Override
    public void unlock() {
        /*
         * TODO 极端情况：线程1获取锁后阻塞，时间长到锁自动释放，此时 线程2来获取锁成功，执行业务
         *  此时线程1活过来执行完业务，二话不说，“误删了”线程2的锁，此时线程3来，获取锁成功
         *  此时线程2、3并行，存在线程安全问题
         * */
        //TODO 解决方案： 既然是误删，所有释放锁行动前，看看是不是自己Thread ID的锁，是的话再删
        //TODO 调用LUA脚本保证原子性,LUA脚本在/src/main/resources/unlock.lua，需要类加载时统一加载这个文件，使用静态变量+静态代码块加载
        //TODO JAVA中RedisTemplate，提供了 execute(脚本，List<K> keys,Object...args)函数，和EVAL是一样的功能

        //TODO 此时，判断ID是否一致和删除锁一起执行，不给高并发下的可乘之机
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),//KEY的名字，使用Collections.singletonList快速创建单元素List
                ID_PREFIX + Thread.currentThread().getId()//当前线程ID
                );
    }

    //老版本删除锁，没考虑Redis执行的原子性
//    @Override
//    public void unlock() {
//        //当前ID
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁的线程ID
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //是自己的锁才能释放
//        if(threadId.equals(id)){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }


}
