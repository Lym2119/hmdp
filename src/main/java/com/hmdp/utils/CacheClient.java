package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

//TODO 缓存工具封装类

@Slf4j//日志
@Component//将这个工具类托管给Spring
public class CacheClient {

    //基于构造函数注入,谁用这个工具，谁传递这个对象
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //TODO 方法1：（针对普通缓存，防止缓存穿透）：将任意JAVA对象序列化为JSON并存储在String类型的KEY中，设置TTL(就是写入缓存)
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //TODO 方法2：（针对热点缓存，逻辑过期防止缓存击穿）：将任意JAVA对象序列化为JSON并存储在String类型的KEY中，设置逻辑过期时间(就是写入缓存+防止击穿)
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //利用RedisData设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入带有逻辑过期时间的缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //TODO 方法3：（针对普通缓存，防止缓存穿透）：根据指定KEY查询缓存，并反序列化为指定类型，利用缓存空值解决缓存穿透
    //TODO 我们是工具类，一切都不能写死：返回值我不确定，得你告诉我啥类型才可以，所以是 泛型 ，再根据传进来的参数，进行泛型匹配加推断
    //TODO 根据id去数据库查询，类型也不一定是Long，同样泛型
    //TODO id前面还有个前缀，类型String，内容也不定，传进来
    //TODO 不同业务，查询数据库的时候，操作不一样，我不可能知道怎么办，所以交给使用者操作数据库本质上是个函数，所以函数式编程即可，令其传入函数
    //TODO 防止缓存穿透式更新缓存，使用this.set函数，还需要两个参数
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        //创建缓存加快访问，以ID作为唯一标识，所以redis缓存的KEY：前缀＋ID
        //数据越是不经常变动，添加缓存的意义越大
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            //如果缓存命中，直接返回
            //Json串转Bean用JSONUtil.toBean
            return JSONUtil.toBean(json, type);
        }

        //TODO Redis中设置了空值，如果命中null，说明处理缓存穿透，需要单独处理
        if(json != null){
            return null;
        }

        //如果缓存未命中，根据ID去数据库读取信息
        //TODO 根据函数式编程，传入一个函数，有参数有返回值，为Function<参数类型，返回值类型>，调用apply去使用
        R r = dbFallback.apply(id);

        //TODO 未读取到商铺信息，返回404，但是注意！！！问题在于：缓存穿透！！！！，此操作不可取
        //TODO 采用插入空值，既然有恶意程序想利用，我就让你空值命中并防止垃圾数据，设置了有效时间，但还是会短时间数据不一致
        if (r == null) {
            //TODO 防止缓存穿透：插入空值
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //读取到商铺信息 ，更新redis
        set(key, r, time, unit);

        //返回
        return r;
    }

    //TODO 方法4：（针对热点缓存，逻辑过期防止缓存击穿）：根据指定KEY查询缓存，并反序列化为指定类型，利用逻辑过期解决热点KEY的缓存击穿
    private static final ExecutorService CACHE_REBUILD_SERVICE = Executors.newFixedThreadPool(10);

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        //注意这里不要直接返回flag，会触发拆箱，有可能空指针，用hutu工具类返回
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        //创建缓存加快访问，以ID作为唯一标识，所以redis缓存的KEY：前缀＋ID
        //数据越是不经常变动，添加缓存的意义越大
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)){
            //不存在，说明非热点数据，返回空
            return null;
        }

        //TODO 命中，解析JSON串，获取数据+逻辑过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject rObject = (JSONObject) redisData.getData();//注意这里，再redisData中，为了啥类型数据都能存，数据类型设置了Object，所以这里的转换出来的实际类型是JsonObject
        R r = JSONUtil.toBean(rObject, type);//JsonObject也是可以用toBean进行翻译的
        LocalDateTime expireTime = redisData.getExpireTime();

        //TODO 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //过期时间在当前时间之后，表示没过期
            //TODO 没过期直接返回
            return r;
        }

        //TODO 过期了尝试获取互斥锁，重建缓存
        //TODO 缓存重建，获取互斥锁，
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        //TODO 获取成功，开启独立线程，恢复缓存
        if(isLock){
            //TODO 对线程的经常性操作：一定使用线程池！！！！！，让线程池提交任务
            //TODO 这个线程要缓存重建，其实就是让一个新线程再走一次缓存预热
            CACHE_REBUILD_SERVICE.submit(() -> {
                try {
                    //获取数据
                    R r1 = dbFallback.apply(id);
                    //写入
                    this.setWithLogicalExpire(key, r1, time, unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                finally {
                    //TODO 解锁这种操作，一定要在finally中，防止出现意外一直锁着
                    unlock(lockKey);
                }
            });

        }

        //TODO 无论获取成功与否，都要返回旧的商铺信息,会存在一段时间的的缓存不一致，取决于重建缓存的速度
        return r;
    }

}
