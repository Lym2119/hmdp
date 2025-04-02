package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    //TODO 通过RedissonClient工厂，可以创建任何好用的redisson提供的工具！

    //TODO RedissonClient在解决Redis主从不一致问题时，未使用主从集群，而是多个独立的功能齐全的Redis节点，每次向每一个节点都申请锁，都成功才可以,以下是三个独立的Redis节点，每个都申请锁，用redissonClient.getMultiLock();联合

    @Bean
    public RedissonClient redissonClient() {
        //1、配置
        Config config = new Config();
        //TODO 如果是集群Redis使用config.useClusterServers()；.setPassword()设置密码
        config.useSingleServer().setAddress("redis://localhost:6379");
        //2、创建
        return Redisson.create(config);
    }

/*    @Bean
    public RedissonClient redissonClient2() {
        //1、配置
        Config config = new Config();
        //TODO 如果是集群Redis使用config.useClusterServers()；.setPassword()设置密码
        config.useSingleServer().setAddress("redis://localhost:6380");
        //2、创建
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient3() {
        //1、配置
        Config config = new Config();
        //TODO 如果是集群Redis使用config.useClusterServers()；.setPassword()设置密码
        config.useSingleServer().setAddress("redis://localhost:6381");
        //2、创建
        return Redisson.create(config);
    }*/

}
