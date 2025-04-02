package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

/*解决缓存一致性！！！！
* TODO 数据库被修改了
* 对于数据一致性需求低的服务：使用redis自带的内存淘汰机制即可完成缓存一致性。
*
* TODO 对于数据一致性需求高的服务：要使用 主动更新 + 设置超时剔除 共同保证一致性。
*
* 对于主动更新，不好的方案：写穿透（因为维护一个能绑定缓存+数据库的系统开发成本大）、写缓存+异步定时写回数据库（写入缓存不稳定，容易丢失）
* TODO 所以使用手动编写一致性编码
* TODO 注意1：使用缓存删除策略，将缓存和数据库解耦，只要缓存失效就删除，防止被数据库更新导致缓存众多无效更新操作
* TODO 注意2：保证缓存删除和数据库更新的原子性，单设备使用事务，分布式设备使用分布式事务解决办法
* TODO 注意3：先写数据库在更新缓存，配合超时时间（读缓存时添加）能最大限度保证线程安全
*
* TODO 综上：读操作->缓存命中读缓存，反之都数据库+写缓存并设置过期时间；写操作->先写数据库再删除缓存（保证原子性）
* */

/* 解决缓存穿透！！！！！！
* TODO 何为缓存穿透：用户请求的数据：1、不在缓存；2、不在数据库
* TODO 此时数据库只能返回null，结果用户继续访问，每次都能到数据库层，一直让调用数据库，结果导致了数据库崩溃
* TODO 被动解决：缓存空对象（还有一种布隆过滤器，但是实现复杂，并且可能误判（他说不存在一定不存在，反之不一定准，小概率吧还会击穿），他在缓存前面加了一层拦截）
* TODO 主动解决：对于字段，增加复杂度，减少被猜到的风险；有良好的格式规范，并进行格式校验；加强用户权限；热点参数做限流
* */

/*解决缓存雪崩！！！！！！！！！！
* TODO 何为缓存雪崩：Redis短时间内有大量Key失效/Redis直接崩溃（更严重） -> 导致海量请求瞬间冲击数据库，造成数据库宕机
* TODO 解决办法：1、（大量Key失效）给每个Key设置随机失效时间
* TODO 解决办法：2、设置Redis集群，利用哨兵快速处理宕机服务，并利用主从同步数据
* TODO 解决办法：3、设置容错机制，出现问题，快速服务降级+拒绝服务，防止请求崩塌到数据库
* TODO 解决办法：4、给业务加多级多层面缓存（一层套一层）增加安全
* */

/*解决缓存击穿！！！！！！！！！！：解决缓存重建时并发线程该如何获取数据（一定不能访问数据库）
 * TODO 何为缓存击穿：“热点KEY失效问题”。Redis中一个并发访问量极高的Key失效了并且重构时间急长且复杂，这个访问请求像子弹一样击穿了数据库
 * TODO 解决办法1互斥锁：同一时间有大量的线程，只有第一个到的获取锁，重建缓存，缓存写入后，释放锁；其余所有线程都在不断，访问缓存失败，获取锁，休眠，一直持续到缓存命中
 * TODO 解决办法1的问题：同一时间有大量的线程，只有第一个在重构缓存，其余的都在等，性能差
 * TODO 解决办法2逻辑过期：不设置TTL（原本问题就是热点KEY的TTL到期导致的），所以没有TTL永不过期，理论上永远能找到，在加缓存中维护一个一个字段时间，标志着啥时候过期
 * TODO 解决办法2逻辑过期：线程1访问缓存发现数据逻辑过期，获取锁，开新线程执行更新缓存，新线程更新完毕释放锁，线程1创建完新线程后，返回旧值；线程2...同样发现数据过期后，发现锁已被占用，说明有人去更新了，直接返回旧值
 * TODO 方法1：内存消耗少，数据一致，简单；但是！！！可能死锁，性能差；方法2：无需等待性能好。但是！！！！！！数据不一致，内存消耗，代码复杂。
 * TODO 方法1、2就是权衡 一致性 和 可用性，按需选择
 * */

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;//工具类作为Spring组件，需要注入使用

    @Override
    public Result queryById(Long id) {
        //缓存穿透（非工具类）
        //Shop shop = queryWithPassThrough(id);

        //缓存穿透（工具类,拉姆达表达式传入函数）
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //缓存穿透（工具类,简写表达式传入函数，正常服务）
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);

        //逻辑过期工具类解决缓存击穿（热点服务，所需数据需要提前写入）
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_HOT_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if(shop == null){
            return Result.fail("店铺不存在!!!");
        }
        //返回
        return Result.ok(shop);
    }

    /*
    * TODO 对各个操作进行封装
    * */

    /*
     * TODO 用互斥锁防止存击穿，并从缓存查询用户
     * */
    public Shop queryWithMutex(Long id) {
        //为商铺查询创建缓存，加快访问，商铺以ID作为唯一标识，所以redis缓存的KEY：前缀＋ID
        //数据越是不经常变动，添加缓存的意义越大
        //进入redis查询缓存Shop ID
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            //如果缓存命中，直接返回缓存中的Shop ID
            //Json串转Bean用JSONUtil.toBean
            return JSONUtil.toBean(shopJson, Shop.class, true);
        }

        //TODO 防止穿透：Redis中设置了空值，如果命中null，说明处理缓存穿透，需要单独处理
        if (shopJson != null) {
            return null;
        }

        //如果缓存未命中，根据ID去数据库读取商铺信息,使用mybatis-plus工具getById直接去数据库查询即可
        //TODO 缓存重建！！！！
        //TODO 1、获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //TODO 2、判断是否获取锁，成功去重建，失败去睡眠等待，睡眠结束后继续执行上述过程，指导锁被开放
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //TODO 3、成功获得锁，重建
            Thread.sleep(200);//模拟重建时间
            shop = getById(id);

            //未读取到商铺信息，返回404，但是注意！！！问题在于：缓存穿透！！！！，此操作不可取
            //采用插入空值，既然有恶意程序想利用，我就让你空值命中并防止垃圾数据，设置了有效时间，但还是会短时间数据不一致
            if (shop == null) {
                //TODO 防止缓存穿透：插入空值
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //读取到商铺信息 ，更新redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //TODO 重建结束，释放锁(必须要try、finally，因为不管这个程序是否成功，都要放锁，要不程序崩溃了)
            unlock(lockKey);
        }
        //返回
        return shop;
    }

    /*
     * TODO 自定义互斥锁操作，因为根据获取锁成功与否不同，执行不同操作，所以自定义
     * TODO 使用Redis：setnx操作 -> 只有众多线程中第一个才能成功执行，后面都不可以；释放锁：删除KEY；防止没人释放锁，设置TTL
     * */

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        //注意这里不要直接返回flag，会触发拆箱，有可能空指针，用hutu工具类返回
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    /*
     * TODO 防止缓存穿透，并从缓存查询用户,未使用工具类
     * */
    public Shop queryWithPassThrough(Long id) {
        //为商铺查询创建缓存，加快访问，商铺以ID作为唯一标识，所以redis缓存的KEY：前缀＋ID
        //数据越是不经常变动，添加缓存的意义越大
        //进入redis查询缓存Shop ID
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //如果缓存命中，直接返回缓存中的Shop ID
            //Json串转Bean用JSONUtil.toBean
            return JSONUtil.toBean(shopJson, Shop.class, true);
        }

        //TODO Redis中设置了空值，如果命中null，说明处理缓存穿透，需要单独处理
        if(shopJson != null){
            return null;
        }

        //如果缓存未命中，根据ID去数据库读取商铺信息,使用mybatis-plus工具getById直接去数据库查询即可
        Shop shop = getById(id);

        //TODO 未读取到商铺信息，返回404，但是注意！！！问题在于：缓存穿透！！！！，此操作不可取
        //TODO 采用插入空值，既然有恶意程序想利用，我就让你空值命中并防止垃圾数据，设置了有效时间，但还是会短时间数据不一致
        if (shop == null) {
            //TODO 防止缓存穿透：插入空值
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //读取到商铺信息 ，更新redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //返回
        return shop;
    }


    /*
    * TODO 第一步，数据预热
    * 缓存击穿针对的都是热点数据
    * TODO 热点数据：都是必须由程序员提前准备的+封装逻辑过期时间，并且进行缓存预热，写入Redis
    * 我们通过单元测试模拟管理员后台维护，完成上面的操作
    * TODO 原有的shop和数据库表都没有逻辑时间字段，所以增加的时候一定不要动，完全可以创建一个新的类，包含逻辑过期时间和原有数据，这样就将二者集合起来
    * */
    public void saveShop2Redis(Long id, Long expireSecond) throws InterruptedException {
        //查询热点店铺
        Shop shop = getById(id);
        Thread.sleep(2000);

        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        //设置逻辑过期时间，加入对象：LocalDateTime.now().plusSeconds获取当前时间并加多少秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));

        //存入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_HOT_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /*
    * TODO 第二步：热点数据预热完成，开始实现逻辑过期，防止缓存击穿
    *  因为热点数据都是提前缓存预热好的，并且逻辑过期，物理上不删除，所以一旦未命中
    *  说明没有提前加入缓存，也就不重要，所以返回null
    *  所以：不考虑缓存穿透
    * */

    //TODO 一旦要操作线程，一定调用ExecutorService下的线程池！！！！！
    private static final ExecutorService CACHE_REBUILD_SERVICE = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        //为商铺查询创建缓存，加快访问，商铺以ID作为唯一标识，所以redis缓存的KEY：前缀＋ID
        //数据越是不经常变动，添加缓存的意义越大
        //进入redis查询缓存Shop ID
        String key = CACHE_SHOP_HOT_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            //不存在，说明不重要的数据，返回空
            return null;
        }

        //TODO 命中，解析JSON串，获取数据+逻辑过期时间
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject shopObject = (JSONObject) redisData.getData();//注意这里，再redisData中，为了啥类型数据都能存，数据类型设置了Object，所以这里的转换出来的实际类型是JsonObject
        Shop shop = JSONUtil.toBean(shopObject, Shop.class);//JsonObject也是可以用toBean进行翻译的
        LocalDateTime expireTime = redisData.getExpireTime();

        //TODO 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //过期时间在当前时间之后，表示没过期
            //TODO 没过期直接返回
            return shop;
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
                    this.saveShop2Redis(id,20L);
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
        return shop;
    }



    //TODO 更新数据库+缓存删除，要保证原子性，单体项目，添加在一个事务@Transactional即可
    @Override
    @Transactional
    public Result update(Shop shop) {
        //ID是shop唯一标识，也适用于缓存的标识，必须对其是否为空判断
        Long id = shop.getId();
        if(id == null){
            return Result.fail("商铺ID不可以为空，无法执行更新操作");
        }

        //mybatis-plus方法updateById利用ID更新数据库单个表
        updateById(shop);
        //删除缓存:有了事务，当以下操作失败，会自动回滚
        //TODO 如果是分布式系统，这里需要另一个系统，即分布式事务，如TCC去完成原子性保证
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    /*
    TODO 点击具体店铺品类，分类别分页显示商铺
     typeId指商铺类型ID，current当前页码，默认为1
     前端未必一定按照地理位置对同类型商铺按照距离排序显示：
     如果给了x,y（地理坐标） 就是对同类型商铺按照距离排序显示；反之就是普通排序分页显示

     TODO 返回值：我们要查询，typeId相同的一堆店铺，他们距离目标点的距离排序以及距离信息（小到大）+对应的店铺信息（共三个，都需要解析出来）
      还需要分页(由于命令只支持从第一个开始，支持截断到end，所以还需要自己再截断出起点from)
    * */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //TODO 1、判断需不需要按照坐标查询
        if(x == null || y == null){
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        //TODO 此时说明我需要按照地理位置进行排序且分页查询
        // 前提：所有的店铺位置已经按类型预热加入缓存（数据库做不了）
        //TODO 普通分页，只需要知道当前页需要显示的表项起始和终止就行，current就是页标(初始默认1)
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //TODO 现在开始根据目标点，在目标商铺范围内以圆形方式查询，返回值要带上距离，对应商铺信息，以及从小到大返回
        // 开始查询：GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANT（在key对应的坐标群中，按照坐标x,y,为圆心，半径10KM为范围，查询，返回值从小到大，结果带距离）
        // 结果中存放：店铺Location(包含店铺ID+坐标点Point) + 距离目标点(x,y)的距离，且按从小到大顺序返回
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),//指定搜索圆心（也可指定已有成员）
                        new Distance(5000),//指定半径，单位默认 米，结果也是米
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance()//指定RedisGeoCommands中的GEO搜索细节，指定返回结果带距离
                                .limit(end)//TODO 指定搜索细节：为了分页，对结果做截取，但是只能是从头截取到一个位置，后面还需要自己截取
                );
        if(results == null){
            //要保证真有东西取出来，要不然会空指针,什么都没有返回空集合
            return Result.ok(Collections.emptyList());
        }

        //TODO 因为指令只支持对于末尾的截断，所以需要我们取出内容，对开头在进行截断
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from){
            //TODO 我们之后要对list进行裁剪，如果总长度都没有裁剪起始位置长，说明没有下一页了结束
            return Result.ok(Collections.emptyList());
        }
        //TODO 使用Stream流实现截取效果;并用.forEach实现遍历效果,解析数据，result表示list中的每一项结果，list是从results中提取的结果，results是从Redis查询结果
        List<Long> ids = new ArrayList<>(list.size());//存储店铺ID列表
        Map<String, Distance> distanceMap = new HashMap<>(list.size());//距离和店铺对应在一起
        list.stream().skip(from).forEach(result -> {
            //TODO 现在遍历的顺序就保证了距离从小到大，现在要统计店铺ID（根据ID再去查店铺具体信息）+ 店铺对应的距离
            //TODO result.getContent()得到的是Location信息，包括了店铺名称+位置信息Point
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //TODO 获取店铺距离，并与店铺对应
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        //TODO 根据shopID的列表批量且有序查询数据库，获取店铺信息
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        //TODO 此时店铺按照距离从小到大且带有店铺信息，只差距离信息了，所以在Shop类加入非表中字段，distance，只需要封装进来就行
        for(Shop shop : shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        };

        return Result.ok(shops);
    }
}
