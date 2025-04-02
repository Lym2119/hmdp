package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    //TODO 基于Redis实现全局唯一ID
    //TODO 尤其是针对订单类的业务，每一个订单都需要一个ID，如果使用数据库的自增长主键，第一太简单容易被用户破解
    //TODO 第二是一但是集群数据库，每一个都从1自增，ID重复会造成问题
    //TODO 所以，Redis实现，他是独立于数据库的，针对数据库的业务，它是基于全局实现的独立唯一性ID
    //TODO 并且Redis也满足了高可用性和高性能
    //TODO Redis中也有自增属性，并且配合存储类型为Long，更适合数据库建立索引，减少内存
    //TODO 单独的Redis自增还不够安全，应该加上一些前缀，保证安全和复杂：共64位，Long类型，各个段分开生成再拼接
    //TODO 第一位：符号位；接着的31位时间戳（获取当前的秒数 - 选定起始时间秒数），单位为秒（共62年）；后32位表示为自增序号（1秒内最多可表示2^32次购买请求）
    //TODO keyPrefix表示为不同业务，用于自增的头部，不同业务需要不同的全局ID

    //TODO 全局唯一ID还有：SnowFlake算法：缺点过度依赖时钟，不准的话有问题，好处：性能更好
    //TODO 全局唯一ID还有：数据库自增：单独拿出一张表实现此业务，由于效率问题，一般会统一拿出大量ID缓存起来

    //设置系统起始时间
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    //最后时间戳和序列号用位运算拼接全局唯一ID，定义一下序列号位数
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //利用Redis，生成自增的序列号
        //TODO 但是一定注意！！！！！！！：同一类型业务，也不能只用keyPrefix去生成序列号，因为Reids本身的上限是64位
        //TODO 在本系统中甚至只有32位，数量更少
        //TODO 所以在生成自增序列号的时候，所使用的Key，需要拼接一些东西：现在是整体都使用这一个大序列号
        //TODO 那我们就模仿全局唯一ID的思路，给Key中再加个字段，变成该业务每天的序列号自增，这样就不会超了
        //TODO 比如 业务名:2022:05:11这种格式，精确到了每一天，每一天都开始一个新的自增ID，每一天的订单数量不可能超2^32，
        // 也可以方便后续业务统计年月日的订单数量,Redis中也会分层级显示更清晰的显示该业务不同日期的订单序列号
        //为序列号获取符合规格的时间戳
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增生成序列号
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + data);

        return timestamp << COUNT_BITS | count;
    }

//    public static void main(String[] args) {
//        //选定系统起始时间
//        LocalDateTime localDateTime = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
//        long epochSecond = localDateTime.toEpochSecond(ZoneOffset.UTC);
//        System.out.println(epochSecond);
//    }

}
