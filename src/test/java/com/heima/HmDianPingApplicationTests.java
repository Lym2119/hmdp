package com.heima;


import com.hmdp.HmDianPingApplication;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoLocation;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

//TODO 此处注入一定要注意！！！！！这是基于Spring Boot的测试
/*
*TODO 测试类默认会从当前包及其父包中搜索 @SpringBootConfiguration（即主启动类 XXXApplication）
*TODO 如果测试类与主启动类不在同一包或子包下，Spring Boot 无法自动找到配置类，导致报错。
* 如果包结构无法调整，直接在 @SpringBootTest 中指定主启动类：@SpringBootTest(classes = HmDianPingApplication.class)
* */
@SpringBootTest(classes = HmDianPingApplication.class)
public class HmDianPingApplicationTests {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //要对Service类中的方法进行测试，就要注入它，才能进行测试（使用谁注入谁）
    @Resource
    private ShopServiceImpl shopServiceImpl;

    @Resource
    private CacheClient cacheClient;//工具类作为Spring组件，需要注入使用

    @Resource
    private RedisIdWorker redisIdWorker;
    //要测试多线程并发的效果，所以使用简单线程池Executors.newFixedThreadPool申请多线程
    private ExecutorService ex = Executors.newFixedThreadPool(500);
    @Test
    void testIDWorker() throws InterruptedException {
        //这个工作需要测试多线程并发获取ID的效果，先定义每一个线程需要干什么
        //TODO 我们要记录整体的生成时间，并且所有线程是异步的，所以需要在整体结束后在记录，用到CountDownLatch

        //共300个线程，这个东西的作用就是对各个线程的执行加以干扰，传入总体是用了多少线程
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            //TODO 利用Runnable定义每一个线程需要的工作:生成100个ID
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println(id);
            }
            latch.countDown();
        };

        //开始记录整体时间
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            //300个线程挨个提交任务执行
            ex.execute(task);
        }
        //等待所有线程执行完毕
        latch.await();
        //记录结束时间
        long end = System.currentTimeMillis();
        System.out.println(end - begin);

    }


    /// 模拟管理员在活动开始前，加入Hot Key操作，并测试
    @Test
    void testSaveHotShop() throws InterruptedException {
        Shop shop = shopServiceImpl.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+1L, shop, 10L, TimeUnit.SECONDS);
    }

    /// 模拟管理员将所有店铺的信息预热加入缓存，GEO模式（经度，纬度，内容），方便后面使用
    //TODO 将数据库中店铺有关的地理坐标数据放入缓存，提前预热，方便业务上线后用户查询
    //TODO 所以这类功能一定要提前按照店铺类型分类，提前预热放到缓存中，GEO存储方式是set，key是店铺类型，score是经纬度换算值，members是店铺ID
    @Test
    void loadShopGEOData() throws InterruptedException {
        //全部店铺信息查询
        List<Shop> list = shopServiceImpl.list();
        //TODO 因为前端必须要按照商铺类型分开显示，分开计算距离用户的距离，所以缓存中也要分开存，店铺类型typeId是key
        // 所以使用Map来存储，其中key使用店铺类型typeId，对应的值是一个Shop列表
        // 先stream化，再重收集即可（如果要类型转化，使用.map即可）
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //TODO 按照类型分批写入redis,就是便利map
        //TODO 使用Map.Entry，遍历Map，分别获取每个类型所有店铺
        for(Map.Entry<Long, List<Shop>> entry : map.entrySet()){
            Long typeId = entry.getKey();
            List<Shop> shops = entry.getValue();
            String key = SHOP_GEO_KEY + typeId;//对应最外面的key，表示同一类型的所有店铺位置
            //TODO 接下来我们可以一条一条的写入Redis，但是效率太低，我们可以先封装一个location的集合，再把集合统一写入
            // 我们需要一个location集合，其内部是RedisGeoCommands.GeoLocation<String>，需要指定其内部数据泛型
            // RedisGeoCommands.GeoLocation<String>内部第一项是members（shopId）、第二项是Point，内部存储x,y坐标
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            for(Shop shop : shops){
                //TODO 遍历该类型所有店铺，把位置信息加入locations，统一写如Redis，减少和Redis交互
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    //TODO 网站的UV统计：统计有多少的用户来过这个网站（每个用户产生一次+1）；PV统计：有多少个人浏览过这个网站，同一个用户可以产生多次
    // 实现这个不可以使用数据库/缓存：因为巨量的数据谁都受不了
    // 所以采用概率算法HyperLogLog,Redis中单个内存占用小于16KB，Redis使用String实现，缺点是有<0.86%的误差，且保证不重复统计（适合UV统计）、
    // 下面模拟一下海量用户场景的HyperLogLog统计精准度以及内存占用
    @Test
    public void testHyperLogLog() {
        String[] users = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            users[j] = "user_" + i;
            if (j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("HLL", users);
            }
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size("HLL");
        System.out.println("count = " + count);
    }

}
