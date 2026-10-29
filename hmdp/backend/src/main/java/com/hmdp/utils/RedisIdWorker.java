package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long EPOCH = 1633072800000L;
    private static final int BIG_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPrefix) {
        //1. 获取当前时间戳
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp = now - EPOCH;
        //2. 获取increment 序列号
        //获取当天日期
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        long increment = stringRedisTemplate.opsForValue().increment("incr:"+keyPrefix+":"+date);
        //3. 组合id
        return (timestamp << BIG_BITS) | increment;
    }
}
