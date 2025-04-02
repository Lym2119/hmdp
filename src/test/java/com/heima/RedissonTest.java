package com.heima;

import com.hmdp.HmDianPingApplication;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootTest(classes = HmDianPingApplication.class)
public class RedissonTest {
    //TODO 测试Redisson连锁：从每个独立节点获取锁，并使用Redisson设置为连锁

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedissonClient redissonClient2;

    @Resource
    private RedissonClient redissonClient3;

    private RLock lock;

    @BeforeEach
    void setUp() {

        //TODO 从每个独立节点获取锁
        RLock lock1 = redissonClient.getLock("order");
        RLock lock2 = redissonClient2.getLock("order");
        RLock lock3 = redissonClient3.getLock("order");

        //TODO Redisson维护创建连锁，各个锁都获取成功，连锁才成功；.getMultiLock谁调用都可以没区别
        //TODO 将每个可重入的，生存时间不给定就启用开门狗刷新锁生存时间的，带有阻塞的单个锁节点给链接起来
        lock = redissonClient.getMultiLock(lock1, lock2, lock3);
    }

    @Test
    void method1() throws InterruptedException {
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if(!isLock){
            log.error("获取锁失败 ... 1");
            return;
        }
        try{
            log.info("获取锁成功 ... 1");
            method2();
            log.info("开始执行业务 ... 1");

        }finally {
            log.warn("准备释放锁 ... 1");
            lock.unlock();
        }
    }
    void method2() {
        boolean isLock = lock.tryLock();
        if(!isLock){
            log.error("获取锁失败 ... 2");
            return;
        }
        try{
            log.info("获取锁成功 ... 2");
            log.info("开始执行业务 ... 2");

        }finally {
            log.warn("准备释放锁 ... 2");
            lock.unlock();
        }
    }
}
