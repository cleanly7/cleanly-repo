package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        //先从Redis中查，这里的常量值是固定的前缀 + 店铺id
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果不为空（查询到了），则转为R类型直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        //否则去数据库中查，查询逻辑用我们参数中注入的函数
        R r = dbFallback.apply(id);
        //查不到，则将空值写入Redis
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //查到了则转为json字符串
        String jsonStr = JSONUtil.toJsonStr(r);
        //并存入redis，设置TTL
        this.set(key, jsonStr, time, timeUnit);
        //最终把查询到的商户信息返回给前端
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        //1. 从redis中查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 空串表示“数据库里也没有”，直接返回空
        if (json != null && StrUtil.isBlank(json)) {
            return null;
        }
        //3. key 不存在（缓存没预热）：查库并写入逻辑过期缓存，不能直接返回空
        if (json == null) {
            return loadAndCache(key, id, type, dbFallback, time, timeUnit);
        }
        //4. 命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.1 兼容非逻辑过期格式（比如以前直接写入的普通 JSON），当作未命中重建
        if (data == null || expireTime == null) {
            return loadAndCache(key, id, type, dbFallback, time, timeUnit);
        }
        R r = JSONUtil.toBean(data, type);
        //5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //6. 未过期，直接返回商铺信息
            return r;
        }
        //7. 过期，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        //8. 获取到了锁
        if (flag) {
            //9. 开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R tmp = dbFallback.apply(id);
                    if (tmp == null) {
                        // 数据库里没有：写空值占位符，防止缓存穿透
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        this.setWithLogicExpire(key, tmp, time, timeUnit);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
            //10. 直接返回商铺信息
            return r;
        }
        //11. 未获取到锁，直接返回商铺信息
        return r;
    }

    /**
     * 缓存未命中/格式不兼容时查库并写入逻辑过期缓存；查不到则写空值占位符
     */
    private <R, ID> R loadAndCache(String key, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.setWithLogicExpire(key, r, time, timeUnit);
        return r;
    }

    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        //先从Redis中查，这里的常量值是固定的前缀 + 店铺id
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果不为空（查询到了），则转为Shop类型直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        R r = null;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            //否则去数据库中查
            boolean flag = tryLock(lockKey);
            if (!flag) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, timeUnit);
            }
            r = dbFallback.apply(id);
            //查不到，则将空值写入Redis
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //并存入redis，设置TTL
            this.set(key, r, time, timeUnit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
