package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.weaver.ast.Var;
import org.jetbrains.annotations.Nullable;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

/*
 * TODO 如何引入多tomcat集群？
 *  1、服务端：复制一个HMDianPingApplication,并设置JVM配置中的端口信息，和原来的不一致 -Dserver.port = ?
 *  2、nginx端：配置nginx.conf，重定向到proxy_pass http://backend;，并设置backend中可用的接口以及负载均衡
 *  如：server 127.0.0.1:8081 max_fails=5 fail_timeout=10s weight=1; server 127.0.0.1:8082 max_fails=5 fail_timeout=10s weight=1;
 *  并重启nginx
 * */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    //TODO 这个业务是针对，秒杀优惠券的，所以使用秒杀券的服务，查询秒杀券信息
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    //TODO 要生成订单，需要全局ID！！！！
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /// 原版业务：同步秒杀。不添加缓存，所有的业务都对接数据库且由一个线程完成，效率低且数据库压力大，只是添加了乐观+悲观锁预防并发安全问题
    //TODO 该业务操作1：秒杀券下单且防止超卖
    //TODO 多表操作：加上事务！！！！！！！！一旦出现问题，可以回滚
    //TODO 基础业务方法：在高并发下，执行后发现，出现了超卖的情况，没有库存依旧卖
    //TODO 高并发，多线程访问共享资源，一个线程需要执行多个步骤，在这其中多个同样线程穿插：加锁！！！！！
    //TODO 悲观锁：线程安全问题一定会发生，严格锁死保持串行，性能低；乐观锁（并发度高，性能好）：线程安全不一定发生，不加锁，只在真正对共享数据操作时判断是否有其他线程对数据修改过（难点）
    //TODO 乐观锁解决方案：版本号法 -> 每条数据都有自己的版本号，被修改一次就会更新，并且操作共享区数据的时候，修改版本以及库存的时候，一定要确认版本没变，反之就说明有线程安全问题
    //TODO 升级方法CAS（Compare And Set）：既然库存本身就会修改，就没必要单独引入字段（影响已有业务），用已有字段代替版本即可
    //TODO 如果有东西可以替代版本号,使用CAS法；反之版本号法
    //TODO 业务2：这种大优惠，不能让黄牛垄断，一个用户限买一次，所以买之前要看用户ID和voucherID是否已经出现了，出现了就不能买
    @Override
/*    public Result seckillVoucher(Long voucherId) {
        //查询秒杀优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //判断是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //秒杀没开始
            return Result.fail("秒杀没开始!");
        }

        //判断是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            //秒杀已结束
            return Result.fail("秒杀已结束!");
        }

        //判断是否有库存
        if (voucher.getStock() < 1) {
            //没有库存
            return Result.fail("库存已不足!");
        }
        Long userId = UserHolder.getUser().getId();

        //TODO 业务2：一人一单
        //TODO 这里加锁，表达的是：函数结束，事务提交之后我才解锁，保证了线程安全，！！！！！！！！“事务和锁的先后顺序一定要搞清楚”
        //TODO 根据！！！！“用户名称”，不同请求对象都不一样，不能用userId，加上toString()也会返回不同对象，再加上.intern()可以，返回字符串常量池，这样就根据字符串名称加锁

        //TODO synchronized仅适合单机模式，集群模式又会出现很多问题
        //老版本：synchronized方案
//        synchronized (userId.toString().intern()) {
//            //去判断能否创建订单并返回全局唯一订单ID
//            //TODO 事务是加在creatVoucherOrder，由Spring管理，所以这个事务其实是加载Spring创建的代理对象上，this对象没有事务（事务失效）
//            //TODO 先获取代理对象（用AopContext.currentProxy()），类型为服务接口，在调用方法
//            //TODO 还需要引入aspectjweaver依赖 + 主启动项@EnableAspectJAutoProxy(exposeProxy = true)暴露代理对象，否则无法获取
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.creatVoucherOrder(voucherId);
//        }


        *//*
         * TODO 如下的解决方案原理：在同一个tomcat的JVM中，synchronized产生一个锁监视器对象，监视所有线程，加锁就是在监视器中记录线程名称，别的线程申请锁因为监视器已经有线程进入而申请失败
         *  一旦当业务部署到多个tomcat的集群时，每个tomcat有自己的JVM，每个JVM有自己的锁监视器，所以当前端请求通过nginx反向代理到n个tomcat中，会有n个线程同时获取锁
         *  当每个线程都是由一个用户发的，那么还是解决不了线程安全，还是出现了一个用户买了多个秒杀券的问题
         *  解决：Redis开发一个跨进程/JVM的全局锁（Redis实现分布式锁）
         * *//*
        *//*
         * TODO 分布式锁：满足分布式/集群模式下”多进程可见且互斥“的锁（锁监视器） + 高可用 + 高性能 + 安全性
         *  Redis利用setnx机制，获取锁，并且一定要添加过期时间（防止服务崩溃而死锁）；释放锁：手动(DEL key)+ 自动过期
         *  为了防止在setnx和设置过期时间之间宕机造成死锁：让二者保持原子性：SET lock threadID EX 10 NX(二者合二为一)
         *  获取锁分为阻塞式（失败了继续等，性能下降实现困难） + 非阻塞式（失败了结束），我们实现： 非阻塞
         * *//*

        //新版本：Redis实现的分布式锁：
        //TODO 创建我们实现的简单锁对象：！！！！！在传KEY的时候，一定要想好锁的业务范围，如果只有"order:"，是对下单业务整体加锁，是不对的，我们只是防止一个人买多了，应该对"order:"下的用户ID加锁
        //TODO 正确的选择Key的范围，锁的越少，性能越好
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        //TODO 这里使用Redisson来尝试
        //TODO Redisson锁：可重入且阻塞式（具体要看使用时传参）
        RLock lockRedisson = redissonClient.getLock("lock:order:" + userId);

        //TODO 这里参：获取失败阻塞时间、生存时间、时间单位（无参表示：非阻塞，生存时间30秒）
        boolean isLock = lockRedisson.tryLock();

        if (!isLock) {
            //TODO 非阻塞式上锁，获取失败会返回值，此时应该选择：报错/继续等待+重试
            //TODO 一人下多单属于是非法行为，所以报错
            return Result.fail("不许重复下单！");
        }
        //TODO 释放锁必须在finally
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.creatVoucherOrder(voucherId);
        }finally {
            lockRedisson.unlock();
        }
    }*/
    //TODO 事务要加载数据库操作上！！！！！！！数据库操作出现问题直接回滚！！！！1
    @Transactional
    public Result creatVoucherOrder(Long voucherId) {//TODO 方法名上不要加锁，这样会锁住对象，导致所有对象串行，性能很低

        Long userId = UserHolder.getUser().getId();

        //TODO 业务2：一人一单
        //TODO 当多线程并发执行时，所有线程都是先执行这一段逻辑，发现订单数量都是0，大家都下去下单了，所以一人一单没实现
        //TODO 这里没办法在放行之前知道是否已经存在订单，因为订单是在后面创建的，所以没办法使用乐观锁方案，只能使用悲观锁
        //TODO 悲观锁需要把下面所有的业务量逻辑锁上，因为单纯锁这里没意义，即使前面线程穿行通过，后面线程来了前面没创建订单还是存在一人多买
        //TODO 如果悲观锁锁住了整个方法也就是this对象，那么所有用户都串行，不合理，我们只是对同一个用户名加锁串行即可
        //TODO 根据！！！！“用户名称”，不同请求对象都不一样，不能用userId，加上toString()也会返回不同对象，再加上.intern()可以，返回字符串常量池，这样就根据字符串名称加锁
        //TODO 这里加锁，是先释放锁再提交的事务（Spring管理），此时数据库没改，锁没了，还是有线程安全问题，所以锁的释放必须在事务之后，锁住整个事务
        //synchronized (userId.toString().intern()) {

        //根据用户、优惠券ID查询是否已经下过单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户不可以重复购买！");
        }

        //TODO 业务1：乐观锁解决超卖（更新数据）
        //TODO 此处添加CAS乐观锁，stock--之前要看stock的值和刚开始是否一致
        //TODO 扣减库存:不能直接使用mybatis-plus固有语句，因为逻辑比较复杂，所以.setsql自己写，再用.eq充当where语句，最后.update执行
        //TODO CAS乐观锁：stock--之前要看stock的值和刚开始是否一致：问题在于，库存数量>0的时候，这种并发获取不会对业务造成安全问题，严格一致太谨慎！！！！成功率低
        //TODO 如果有的业务就需要强制严格的判断相等才可以，我们可以分段锁，将数据平铺到多个数据库，我们对多个数据库挨个上锁，这样就会提高成功率
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)// where语句 '='
                .gt("stock", 0)//TODO CAS乐观锁改进：stock--之前再次查询库存，这时候还有 >0 就可以卖！！！不必严格一致
                .update();
        if (!success) {
            //扣减库存失败：没有库存
            return Result.fail("库存已不足!");
        }

        //创建订单:不需要订单的全部信息在，只需要：全局唯一订单ID、用户ID、秒杀券ID
        VoucherOrder voucherOrder = new VoucherOrder();
        //获取全局唯一ID
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户ID
        //Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //秒杀券ID
        voucherOrder.setVoucherId(voucherId);
        //更新订单信息
        save(voucherOrder);
        return Result.ok(orderId);
    }


    /// 业务升级：异步下单（服务员<前端缓存操作> + 厨师<后端数据库操作>模式）
    //TODO 千万注意！！！！！！，异步秒杀实现的前提，必须提前在缓存中预热上秒杀券信息，前台服务都在缓存中进行的！！！！！

    /*
    * TODO 业务大修改：在保持锁不变的时候添加异步操作，数据库读交给缓存，数据库写交给异步线程慢慢完成，增加效率且降低数据库压力
    *  类比餐厅模式：执行较快的数据库查询工作（购买资格和一人一单判断）提前预热到redis中，交给服务员线程快速处理：
    *  如果可以下单：返回前端订单ID（此时已经下单成功，即使没立即写入库，但在后面也会慢慢写入），给后端厨师线程的任务列表（阻塞队列）里返回（用户ID、优惠券ID、订单ID）
    *  厨师线程慢慢按照数据库可以接受的速度做写入，前后分离，多线程异步操作，提高整体效率
    *  缓存中：key-string存储优惠券剩余数量（在写入优惠券时预热缓存）；key-set存储同一个优惠券，有哪些用户来了(set中)，主业务执行时缓存
    *
    *  TODO 总结：拆解数据库擦操作，复杂的写交给后台线程异步完成；简单的数据库读交给前台线程异步完成且使用缓存；前台分别给后台和用户发“小票”信息
    * */

    //创建阻塞队列(后台线 程从此处获取数据)：当没有元素时，获取元素的线程会等待，直到有元素进来时才会唤醒等待线程
    // 阻塞队列使用的问题：基于JVM内存，限制队列大小导致过多订单放不下；反之容易爆内存。一旦JVM宕机，或者取出订单后宕机，订单信息没了，但是已经告诉用户购买成功了，数据安全有问题。
    // 新版本不使用阻塞队列！！！！！，改用Redis实现的消息队列（基于Stream结构 + 消费者组结构）
    //private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    /// 加载LUA脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        //静态代码块初始化静态值
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        //TODO 指定文件位置，使用ClassPathResource,让程序去ClassPath下的Resource文件找，()内直接指定名称即可
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /// 后台任务的创建(要在类加载时候创建，巡逻自己的阻塞队列)
    //TODO 1.获取Redis实现的消息队列中的消息（基于Stream结构 + 消费者组结构）
    //TODO 2.创建后台线程：前台抢单完成，创建线程异步写入库，需要 —> 线程池 && 线程任务（速度无所谓，单线程即可）
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //TODO 4.后台线程在这个服务类初始化之后就要马上启动，这个后台线程必须最开始就要不断扫描自己的任务阻塞的队列，有活就马上干，使用@PostConstruct，让Spring帮我们实现类加载后马上创建后台 + 提交任务
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderTask());
    }
    //TODO 3.线程任务
    private class VoucherOrderTask implements Runnable {
        String queueName = "stream.orders";
        /// 后端处理程序：run()，后端线程执行的真正任务
        @Override
        public void run() {
            while (true) {
                try {
                    //TODO 3.1.获取消息队列中的订单信息：XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >（命令版本）
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "r1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //TODO 3.2.如果消息获取失败：继续循环获取
                    if(list == null || list.isEmpty()){
                        continue;
                    }
                    //TODO 3.3.解析订单信息并封装
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();//得到参数列表，转成目标类
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //TODO 3.4.如果消息获取成功：在数据库中完成下单（写入库）
                    handleVoucherOrder(voucherOrder);
                    //TODO 3.5.ACK确认:SACK streams.order g1 id(消息ID)
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    //TODO 3.6.抛出异常，说明未确认：去pending-list处理未正常确认的数据
                    log.error("处理订单异常！即将处理pending-list中数据");
                    handlePendingList();
                }
            }
        }
        //TODO 3.6.处理订单异常！即将处理pending-list中数据
        private void handlePendingList() {
            while (true) {
                try {
                    //TODO 3.1.获取pending-list中的订单信息：XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0（命令版本）
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "r1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //TODO 3.2.如果消息获取失败：pending-list没数据，没有未确认的消息，异常处理结束
                    if(list == null || list.isEmpty()){
                        break;
                    }
                    //TODO 3.3.解析订单信息并封装
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();//得到参数列表，转成目标类
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //TODO 3.4.如果消息获取成功：在数据库中完成下单（写入库）
                    handleVoucherOrder(voucherOrder);
                    //TODO 3.5.ACK确认:SACK streams.order g1 id(消息ID)
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    //TODO 这里抛出异常，直接继续循环去pending-list处理未正常确认的数据
                    log.error("处理pending-list订单异常！即将再次循环处理pending-list中数据");
                    //防止一直抛异常，可以休眠，再去处理
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    //TODO 5.获取代理：代理获取需要在主线程之中，后台线程是子线程，做不到，所以要再前台主线程获取
    private IVoucherOrderService proxy;
    //TODO 4.线程任务中，处理订单持久化到数据库的方法
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //TODO 获取用户：注意这里不能用UserHolder，UserHolder只能在主线程用，子线程用不了
        Long userId = voucherOrder.getUserId();
        //TODO 这里加不加分布式锁都无所谓，因为前台LUA脚本已经可以满足线程安全，并且判断一人一单，这里只是以防万一
        RLock lockRedisson = redissonClient.getLock("lock:order:" + userId);
        //获取失败阻塞时间、生存时间、时间单位（无参表示：非阻塞，生存时间30秒）
        boolean isLock = lockRedisson.tryLock();
        if (!isLock) {
            //此处只是保底加锁，理论上不可能除法
            log.error("不许重复下单！");
            return;
        }
        //TODO 释放锁必须在finally;先提交事务再放锁
        try {
            //子线程获取不到代理，为了获取事务去写数据库，必须得到这个代理
            //IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.creatVoucherOrder2(voucherOrder);
        }finally {
            lockRedisson.unlock();
        }
    }
    //TODO 6.creatVoucherOrder函数逻辑重写，让他执行数据库写操作，单独拎出啦主要是保证这个函数的事务先做完，再释放锁
    @Transactional
    public void creatVoucherOrder2(VoucherOrder voucherOrder) {//TODO 方法名上不要加锁，这样会锁住对象，导致所有对象串行，性能很低
        //获取用户：注意这里不能用UserHolder，UserHolder只能在主线程用，子线程用不了
        Long userId = voucherOrder.getUserId();

        //根据用户、优惠券ID查询是否已经下过单，几乎不可能进入这里，只是做个一人一单的保底
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户不可以重复购买！");
            return;
        }

        //TODO 业务1：乐观锁解决超卖（更新数据）
        //TODO 此处添加CAS乐观锁，stock--之前要看stock的值和刚开始是否一致
        //TODO 扣减库存:不能直接使用mybatis-plus固有语句，因为逻辑比较复杂，所以.setsql自己写，再用.eq充当where语句，最后.update执行
        //TODO CAS乐观锁：stock--之前要看stock的值和刚开始是否一致：问题在于，库存数量>0的时候，这种并发获取不会对业务造成安全问题，严格一致太谨慎！！！！成功率低
        //TODO 如果有的业务就需要强制严格的判断相等才可以，我们可以分段锁，将数据平铺到多个数据库，我们对多个数据库挨个上锁，这样就会提高成功率
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())// where语句 '='
                .gt("stock", 0)//TODO CAS乐观锁改进：stock--之前再次查询库存，这时候还有 >0 就可以卖！！！不必严格一致
                .update();
        if (!success) {
            //扣减库存失败：没有库存
            log.error("库存已不足!");
            return;
        }

        //创建订单
        save(voucherOrder);
    }

    /// 前台任务的创建，任何请求都是先进入这里、

    //TODO 千万注意！！！！！！，异步秒杀实现的前提，必须提前在缓存中预热上秒杀券信息，前台服务都在缓存中进行的！！！！！

    //TODO 下面是：使用了 Redis中Stream结构+消费者组构成的 消息队列 -> 实现新业务流程代码
    //TODO 将老业务中数据库查询操作移出来，交给缓存，且使用LUA解决并发安全；将数据库写移到子线程，单独一个线程在后台慢慢搞
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userID = UserHolder.getUser().getId();
        //订单ID
        long orderID = redisIdWorker.nextId("order");

        //TODO 1.执行LUA脚本，不需要KEY类型参数（传空list），两个其他参数（用户ID、优惠券ID）
        //TODO 用户下单，首先是缓存处理，数据库异步写入更新，最终而这可以同步
        //TODO 先判断有没有资格下单
        //TODO 有资格下单后，给后台、顾客发送消息（顾客得到订单ID；后台在消息队列得到用户ID+优惠券ID+订单ID）,并且这个操作可以直接集成在一起，都是对Redis操作
        /// 使用了LUA，将库存和一人一单封装，此时只有一句JAVA代码，Redis每次必须完整执行完LUA脚本才会执行下一个，所以在高并发时候，这里满足了并发安全
        /// 按照原本业务的一人一单分布式锁（setnx/Redisson），当同一用户发送了若干秒杀请求，每次Redis只允许一个请求执行脚本，因为这里是原子性的
        /// 所以这里一人一单在高并发下没有现成穿插执行的安全问题，可以使用Redis缓存判断一人一单，等于用LUA脚本+缓存：既判断了，又加锁了
        /// 后面写入数据库为了严谨，也加了分布式锁，只是为了以防万一
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userID.toString(), String.valueOf(orderID)
        );

        //TODO 2.对购买资格进行判断，返回值大于0都是异常,没有购买资格
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1? "库存不足" : "不能重复下单");
        }

        //TODO 3.帮助后台线程获取代理：此时前台已经把小票进行了后台和用户的分发工作！但是由于后台操作需要获取代理(代理才能有Spring事务),需要在主线程获取
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //TODO 4.将订单ID小票给用户
        return Result.ok(orderID);
    }

    // 下面是：使用了阻塞队列实现新业务流程代码
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        Long userID = UserHolder.getUser().getId();
        //TODO 1.执行LUA脚本，不需要KEY类型参数（传空list），两个其他参数（用户ID、优惠券ID）
        //TODO 用户下单，首先是缓存处理，数据库异步写入更新，最终而这可以同步
        /// 使用了LUA，将库存和一人一单封装，此时只有一句JAVA代码，Redis每次必须完整执行完LUA脚本才会执行下一个，所以在高并发时候，这里满足了并发安全
        /// 按照原本业务的一人一单分布式锁（setnx/Redisson），当同一用户发送了若干秒杀请求，每次Redis只允许一个请求执行脚本，因为这里是原子性的
        /// 所以这里一人一单在高并发下没有现成穿插执行的安全问题，可以使用Redis缓存判断一人一单，等于用LUA脚本+缓存：既判断了，又加锁了
        /// 后面写入数据库为了严谨，也加了分布式锁，只是为了以防万一
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userID.toString()
        );

        //TODO 2.对购买资格进行判断，返回值大于0都是异常,没有购买资格
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1? "库存不足" : "不能重复下单");
        }

        //TODO 3.有资格下单，给后台、顾客发送小票（顾客得到订单ID；后台得到用户ID+优惠券ID+订单ID）
        //订单ID
        long orderID = redisIdWorker.nextId("order");
        //TODO 用户ID+优惠券ID+订单ID封装VoucherOrder，放入后台阻塞队列
        //封装订单基本信息
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderID);
        voucherOrder.setUserId(userID);
        voucherOrder.setVoucherId(voucherId);
        //TODO 将订单基本信息放入阻塞队列，交给后台进程异步写入数据库
        orderTasks.add(voucherOrder);

        //TODO 4.此时前台已经把小票进行了后台和用户的分发工作！但是由于后台操作需要获取代理(代理才能有Spring事务),需要在主线程获取
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //TODO 5.将订单ID小票给用户
        return Result.ok(orderID);
    }*/
}

