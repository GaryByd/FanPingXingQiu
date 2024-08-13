package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    private static final long COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPrefix) {
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //生成序列号
        String key = "icr:" + keyPrefix +":"+date;

        //不用管这个空指针
        long count = stringRedisTemplate.opsForValue().increment(key);
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
       LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
       long second = time.toEpochSecond(ZoneOffset.UTC);
       System.out.println("second = " + second);
    }
}
