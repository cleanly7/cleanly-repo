package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //校验用户是否登录
        //1.获取请求头的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        //2.基于token获取redis用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+token);
        //3.判断用户对象是否存在
        if(userMap.isEmpty()){
            return true;
        }
        //4.将user hash结构转换为UserDTO对象
        UserDTO userDTO = new UserDTO();
        BeanUtil.fillBeanWithMap(userMap, userDTO, true);
        //5.存在，将用户存放在ThreadLocal中
        UserHolder.saveUser(userDTO);
        //6.刷新token有效期
        stringRedisTemplate.expire(token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }
}
