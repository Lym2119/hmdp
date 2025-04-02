
--- 这里面是脚本内容，需要传参的，判断好是否是Key类型
--- 是的话使用KEYS数组传进来
--- 不是的话用ARGV数组传进来
--- 从1开始，判断好两类型参数位置

--- 锁的KEY：根据业务会变得，应该不写死
local key = KEYS[1]

--- 当前线程ID，不能写死！（用于和数据库中取到的ID比较看看是不是同一个线程）
local threadID = ARGV[1]

--- 根据KEY获取锁中线程的标识
local id = redis.call('get', key)

--- 比较获取到的锁中线程ID和当前线程ID，一致才能删除锁
if(id == threadID) then
    --- 释放锁
    return redis.call('del', key)
end
--- 不是自己的锁，返回0
return 0