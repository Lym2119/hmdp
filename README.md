# 代码使用说明(本项目来自b站[黑马程序员](https://space.bilibili.com/37974444)[redis教程](https://www.bilibili.com/video/BV1cr4y1671t)，这里仅作为自己学习使用)

项目代码包含：
- master : 主分支，包含完整的实现代码，以及全部注释 + 全部的额外辅助文件
- 前端资源在src/main/resources/nginx-1.18.0下

视频地址:
- [黑马程序员Redis入门到实战教程，深度透析redis底层原理+redis分布式锁+企业解决方案+redis实战](https://www.bilibili.com/video/BV1cr4y1671t)
- [https://www.bilibili.com/video/BV1cr4y1671t](https://www.bilibili.com/video/BV1cr4y1671t)
  - P24起 实战篇

实用笔记地址：
- 基础篇：https://cyborg2077.github.io/2022/10/21/RedisBasic/#%E5%88%9D%E8%AF%86Redis
- 实战篇：https://cyborg2077.github.io/2022/10/22/RedisPractice/#%E5%86%85%E5%AE%B9%E6%A6%82%E8%BF%B0

## 1.Windows部署
- 需要Redis，无密码，默认IP和端口（如果多个Redis，端口从6379自增），版本在6.2以上，先将dump.rdb放在Redis主目录下，再启动Redis，用以恢复数据。
- 需要MySQL，密码见手机备忘录，默认IP和端口，版本使用MySQL8，先创建hmdp数据库，再导入hmdp_data.sql，用以恢复数据。
- 建议安装Redis、MySQL的图形化工具方便开发
- 需要安装LUA5.1环境 + JDK17 + IDEA2024.3.3（安装LUA插件）
- 开发的时候，Postman会当作测试工具或者管理员用以预热缓存
- Jemeter会用来测量高并发场景下的性能以及正确性
- UploadController中第一个方法uploadImage中，IMAGE_UPLOAD_DIR常量，需要修改为hmdp\src\main\resources\nginx-1.18.0\html\hmdp\imgs这个文件真实地址，才能实现传照片发博客功能


## 2.技术栈 && 实现功能

技术栈：Redis + Spring Boot + MySQL + 基于Redis的stream结构的MQ + Mybatis-plus（工具） + Hutool（工具） + JWT

实现功能：短信验证码登录、查找最近店铺、优惠券秒杀、关注推送、发表点评的完整业务流程。
- 使用redis+JWT解决了在集群模式下的Session共享问题，使用拦截器实现用户的登录校验和Token刷新。
- 使用Cache Aside模式实现缓存，并解决数据库与缓存一致性问题。
- 使用redis对高频访问的信息进行缓存预热，用缓存空值解决缓存穿透，用随机ttl解决缓存雪崩，用逻辑过期解决缓存击穿。
- 使用乐观锁解决超卖问题。
- 使用基于setnx操作的redis分布式锁/Redisson提供的分布式锁解决了在集群模式下一人一单的线程安全问题。
- 使用lua脚本 + redis单线程特性 + Redis的stream结构的MQ实现高并发场景下的异步秒杀。
- 使用ZSet实现了点赞排行榜功能，用set以及集合运算实现关注和共同关注功能。基于Feed流推模式实现关注推送，并按照发布时间实现滚动分页显示。
- 使用GEO结构实现了商户与用户距离从小到大排序展示、bitmap实现用户签到、UV统计功能。
