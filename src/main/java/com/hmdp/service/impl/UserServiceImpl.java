package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 *
 * 写服务一定要逻辑清晰，不同服务、工具方法用函数调用
 *
 * UserServiceImpl 继承自 ServiceImpl
 * 由mybatis-plus提供，轻松完成单表增删改查，传入Mapper以及对应类，类中注解说明对应的数据库表
 */

/*
*
* 基于session实现登录+注册—+验证登录有问题
* 一旦tomcat服务器做横向扩展，因为session是在各个tomcat中独立存储，所以会出现
* 各个session存储不一致问题，导致登录出问题
* 同时保证：基于内存、K-V对、内存一致：用redis数据库（因为他是独立于tomcat且都可访问这个数据库）
* 后面会对一些session流程进行修改
*
* 使用Redis注意：
* 先想好Value存哪种类型：Object存Hash、String存String
* 在想好Key：保证唯一且别人能得到并且拿着Key来取数据
* key最好前面要加上业务前缀，让存储结构更加清晰
* 要设置好有效期，防止数据无限制堆积
*
* */


@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    //服务接口定义要干啥，接口和类分离，用累实现接口完整服务

    //服务实现时，一定要先写流程图，根据流程图进行书写

    //Resource:基于名字装配（先名字后类型）、Autowired:基于类型装配（先类型后名字）
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //验证手机号:正则表达式，在RegexPatterns存储正则表达式（业务1）+RegexUtil验证正则（业务2），不同业务要分离
        //一些在业务中用到的工具，除了第三方提供的，自己写的话，要将其放在Utils业务下面，实现分离
        if(RegexUtils.isPhoneInvalid(phone)) {
            //如果手机号不对，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        //手机号正确，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //验证码用于辅助登录，存session,用setAttribute方法
        //session.setAttribute("code", code);

        //TODO 更新方法：使用Redis,常见常量一定要单独定义
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //返回验证码（日志中模拟发送，可以后期调用服务实现），在控制台输出
        log.debug("发送短信验证码成功，验证码{}", code);

        //返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //验证手机号：只要是前端数据，都要进行规格审查
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            //如果手机号不对，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        //验证验证码是否正确：先从session中获取提前村好的，在对比已有的
        //输入验证码为空或者二者不匹配都是错

        //老方法：redis
//        Object cacheCode = session.getAttribute("code");
//        String code = loginForm.getCode();
//        if(code == null || !cacheCode.toString().equals(code)) {
//            //不一致报错
//            return Result.fail("验证码错误");
//        }

        //TODO 更新为：从redis取验证码进行校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null){
            return Result.fail("该手机号并未获得验证码");
        }
        if(code == null || !cacheCode.equals(code)) {
            //不一致报错
            return Result.fail("验证码错误");
        }

        //已有数据都验证完毕，现在要看这个用户在不在系统，在的话直接记录进入session，反之调用急速注册在记录session
        //手机号唯一标识(多个查询返回.list())， select * from tb_user where phone = ?用mybatis-plus辅助
        User user = query().eq("phone", phone).one();

        //用户不存在，需要转去快速注册，只需要存电话，昵称随机，其他都可以空这
        if(user == null) {
            //注册完之后也要保持登录，记录session，所以要返回user,另一个服务，单独完成
            user = creatUserWithPhone(phone);
        }

        //这次登陆完成，如果以后还需要查询是否处在登陆状态，直接插缓存，所以必须写入session
        //这里注意：直接存入全部的信息：信息泄露+服务器内存压力大
        //所以千万记住，前后端返回数据/服务器存的数据等流通的数据，只要不是数据库中的数据
        //都要进行减配，只保留关键数据，
        //比如user对应数据库表，流通时使用减配UserDTO类
        //使用huto工具类：BeanUtil.copyProperties(数据源，目标类)拷贝属性方法，返回目标类对象

        //老方法：session
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        //TODO 更新方法为 Redis
        //TODO 生成Redis中用于唯一表示用户的KEY：UUID生成随机token（登陆令牌，最后需要传递给前端，以后请求都要带着，验证身份）
        String token = UUID.randomUUID().toString(true);

        //TODO 将User对象做简化处理：UserDTO,再将其转化为HashMap，因为Redis中存储对象用Hash
        //TODO 千万注意，stringRedisTemplate要求所有字段全是String，但是userDTO有字段是Long，不可以会报错！！！！
        //TODO 还是刚才的BeanUtil.beanToMap方法，但是他可以支持自定义！！！！！
        //TODO 传入类+new一个HashMap+CopyOptions.create()开始自定义转换方法
        //TODO ignoreNullValue :转换的时候忽略空白值、setFieldValueEditor转换键值对的时候自定义操作
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> usermap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .ignoreNullValue()
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
                );

        //TODO 存储：千万注意，stringRedisTemplate要求所有字段全是String，但是userDTO有字段是Long，不可以会报错！！！！
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, usermap);

        //TODO 一定记得，Redis数据必须有有效期，S防止无限添加，模仿session，设置为30min:但是，也同样要保证，只要访问一次，就要重置30分钟，所以需要在拦截器中增加这个逻辑
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        //TODO 将登陆令牌token传递回前端
        return Result.ok(token);

        //不需要返回登录凭证，本方法基于session，将session ID放在cookie中，且唯一标志，每次拿ID可找到session也就是有用户信息
        //return Result.ok();
    }

    private User creatUserWithPhone(String phone) {
        //根据两条信息：只需要存电话，昵称随机，其他都可以空这，快速创建
        User user = new User();
        user.setPhone(phone);
        //前缀+随即名称：更加规整、固定前缀要在工具类写死
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //存入数据库
        save(user);
        return user;
    }

    /// 实现用户签到，没有任何参数+返回值，使用Redis的Bitmap结构
    //TODO 我们使用Redis的Bitmap结构，一个Key中存储用户+年+月，value存的是bitmap类型，每一位表示这个月中每一天的签到情况
    // Redis底层使用String类型实现bitmap，上限512M，2^32个bit；Spring在封装的时候，将之放到了.opsForValue
    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //TODO 拼接Key:包含用户+年+月
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //TODO 获取今天是本月第几天，作为 offset
        int dayOfMonth = now.getDayOfMonth();
        //写入Redis：SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /// 实现:当前用户截止到今天的，在本月的连续签到天数（从最后一次签到往前数，直到全部遍历或者遇到未签到）
    /// 没有传递进来的参数，返回连续签到天数
    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //TODO 拼接Key:包含用户+年+月
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //TODO 获取今天是本月第几天，作为 offset
        int dayOfMonth = now.getDayOfMonth();

        //TODO 利用BITFIELD查询一个范围内的bitmap值：BITFIELD key GET(bitfield子命令) u14(返回的十进制类型+bitmap中获取几位) 0(起始位置)
        // .opsForValue().bitField首先传入Key，然后就是对子命令的创建BitFieldSubCommands.create()+选择子命令类型Get（选择返回数据类型+位数）+valueof(起始位置)
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        //健壮性判断
        if(result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0) {
            return Result.ok(0);
        }
        //TODO 对得到的本月截止到今天的 十进制表示的bitmap，进行位运算逐位遍历，统计出连续登陆天数
        int count = 0;
        while(true) {
            if((num & 1) == 0){
                break;
            }else{
                count++;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
