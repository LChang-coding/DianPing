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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //手机号格式不正确
            return Result.fail("手机号格式不正确");
        }
        //校验验证码
        //从reidis中获取验证码并校验
        String cacheCode=stringRedisTemplate.opsForValue().get("login:code:"+phone);//正确的验证码
        String code=loginForm.getCode();//用户输入的验证码
        if(cacheCode==null||!cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //根据手机号查数据库
        User user=query().eq("phone",phone).one();
        if(user==null){
            //注册用户 ->创建新用户
            user=createUserWithPhone(phone);
        }
        //保存用户信息到redis中
        //生成随机token 作为登陆令牌
        String token = UUID.randomUUID().toString(true);
        //将user存储到redis中
        UserDTO userDTO=BeanUtil.copyProperties(user, UserDTO.class);
        Map<String,Object> map=BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //将dto的每个字段转化成键值对
        stringRedisTemplate.opsForHash().putAll("login:token:"+token,map);
        //设置有效期
        stringRedisTemplate.expire("login:token:"+token,30, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //创建用户
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);//保存用户到数据库
        return user;
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //手机号格式不正确
            return Result.fail("手机号格式不正确");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到redis
        stringRedisTemplate.opsForValue().set("login:code:"+phone,code,2, TimeUnit.MINUTES);//2分钟过期
        log.debug("发送短信验证码成功，验证码：{}",code);

        return Result.ok();
    }
}
