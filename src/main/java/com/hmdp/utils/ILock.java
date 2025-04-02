package com.hmdp.utils;

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


public interface ILock {
    /*
    * TODO 尝试获取锁（因为是非阻塞，尝试成功返回true/反之不等待结束返回false）
    *  @param timeoutSec :锁的超时时间，超时自动解除
    * */
    boolean tryLock(long timeoutSec);

    /*
    * TODO 手动释放锁
    * */
    void unlock();
}
