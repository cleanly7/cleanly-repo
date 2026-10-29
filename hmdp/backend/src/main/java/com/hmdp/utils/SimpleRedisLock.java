package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{

    private final String name;
    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private final static String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private final static String LOCK_PREFIX = "lock:";
    private final static DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public boolean tryLock(long timeOut, TimeUnit timeUnit) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        return Boolean.TRUE.equals(stringRedisTemplate
                .opsForValue()
                .setIfAbsent(LOCK_PREFIX + name, threadId, timeOut, timeUnit));
    }

    @Override
    public void unlock() {
        //原子性 查找和释放锁 解决线程安全问题(查找后阻塞线程)
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
}
