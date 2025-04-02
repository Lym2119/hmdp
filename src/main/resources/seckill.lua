
--- 使用Stream以及消费者组创建的消息队列，更新后的全新异步秒杀任务：反正订单基本信息也要放在Redis，不如在判断完资格后直接放进来，减少一次JAVA与Redis交互

--- 1、需要传入进来的东西：参数列表
--- 1.1.优惠券ID(我们查缓存需要的KEY：前缀+优惠券ID)
local voucherID = ARGV[1]
--- 1.2.用户ID(用于判断一人一单，看优惠券ID的购买人set有没有这个用户ID)
local userID = ARGV[2]
--- 1.3.订单ID
local orderID = ARGV[3]


---2、数据KEY
---2.1.查询库存KEY,用..拼接
local stockKey = 'seckill:stock:' .. voucherID
---2.2.查询一人一单KEY
local orderKey = 'seckill:order:' .. voucherID

---3、脚本业务 -> 判断用户有没有资格下单：有库存 && 第一次下单
---3.1.判断库存是否充足（redis.call返回String，与数字比较需要加转化tonumber()）
if(tonumber(redis.call('get', stockKey)) <= 0) then
    ---3.1.2.库存不足，返回1
    return 1
end
---3.2.判断用户是否重复下单：SISMEMBER orderKey userID
if(redis.call('sismember', orderKey, userID) == 1) then
    ---3.2.2.用户已经下单，返回2
    return 2
end
---3.3用户满足下单条件，扣减库存+将用户存储到对该优惠券下单set中,并返回0
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userID)

--- 4.当有资格购买，直接给消息队列发消息即可:XAA stream.orders * k1 v1 k2 v2 ...
--- 注意，在数据库以及对应订单这个类中，订单ID标识为 id，所以这里也这么存，方便取数据转化成对应类
redis.call('xadd', 'stream.orders', '*', 'userId', userID, 'voucherId', voucherID, 'id', orderID)
return 0