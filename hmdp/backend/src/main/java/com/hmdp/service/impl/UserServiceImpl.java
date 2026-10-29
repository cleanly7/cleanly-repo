package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 验证手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 手机号格式无效
            return Result.fail("手机号格式无效");
        }
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3. 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 4. 发送验证码
        log.debug("发送验证码：" + code + "给手机号：" + phone);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 验证手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 手机号格式无效
            return Result.fail("手机号格式无效");
        }
        // 2. 校验验证码
        String Cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (Cachecode == null || !Cachecode.equals(loginForm.getCode())) {
            // 验证码不一致，报错
            return Result.fail("验证码不一致");
        }
        // 4. 一致， 根据手机号查询用户
        User user = query().eq("phone", phone).one();
        if(user == null) {
            // 5. 不存在，创建用户并保存
            user = createUserWithPhone(phone);
        }
        // 6. 保存用户到redis
        //6.1 生成token
        String token = UUID.randomUUID().toString();
        //6.2 将user对象转为hash存储
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                new CopyOptions()
                        .ignoreNullValue()
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        //6.3 存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);
        //6.4 设置过期时间
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //7. 返回结果到客户端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //2. 保存用户
        save(user);
        return user;
    }

    @Override
    public Result logout() {
        //1. 获取登录用户
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null) {
            //2.未登录，直接返回
            return Result.fail("未登录");
        }
        //3. 登出，删除redis中的用户信息
        stringRedisTemplate.delete(LOGIN_USER_KEY + userDTO.getId());
        //4. 返回结果
        return Result.ok();
    }
}
