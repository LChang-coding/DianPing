package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";//唯一标识前缀，区分不同的JVM进程
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程的标识
        String id = ID_PREFIX+Thread.currentThread().getId();//拼接上线程id，区分不同的线程
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, id, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//避免自动拆箱引起的空指针异常
    }

    @Override
    public void unlock() {
        //获取当前线程的标识
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断是否相等
        if (threadId.equals(id)) {
            //相等，释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
