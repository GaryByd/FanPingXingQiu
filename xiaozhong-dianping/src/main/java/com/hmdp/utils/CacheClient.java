package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit
    )
    {
        String key = keyPrefix+id;
        //1.从redis查询商铺
        String Json =  stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(Json)){
            //3.存在，type
            return JSONUtil.toBean(Json, type);
        }

        //判断是否命中的为空值
        if(Json != null){
            //返回一个错误信息
            return null;
        }

        R r = dbFallback.apply(id);
        //5.数据库中也不存在 返回错误
        if(r == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis
        this.set(key,r,time,unit);
        return r;
    }


    //还没有测试过
    public <R,ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type,Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix+id;
        //1.从redis查询商铺
        String Json =  stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(Json)){
            //3.存在，直接返回
            return JSONUtil.toBean(Json,type);
        }
        //判断是否命中的为空值
        if(Json != null){
            //返回一个错误信息
            return null;
        }
        //4.不存在，查询数据库
        //实现缓存重建
        //缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        R r1 = null;
        //判断是否获取成功
        try {
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(keyPrefix,id,type,dbFallback,time,unit);
            }else{
                //成功，根据id查询数据库
                r1 = dbFallback.apply(id);
                //数据库中不存在
                if( r1 == null){
                    //将空值写入redis
                    this.set(key, null, time, unit);
                    return null;
                }
            }
        }catch (InterruptedException e){
            e.printStackTrace();
        }finally {
            unLock(lockKey);
        }
        return r1;
    }



        private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
        public <R,ID> R  queryWithLogicalExpire( String keyPrefix, ID id, Class<R> type,Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix+id;
        //1.从redis查询商铺
        String Json =  stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(Json)){
            //3.存在，直接返回
            System.out.println("缓存命中");
            return null;
        }
        //命中需要判断过期时间
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            return r;
        }
        //已经过期重建
        String lockKey = LOCK_SHOP_KEY+id;
        //获取互斥锁
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException("重建失败");
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });

        }
        return r;
        //成功开启独立线程实现重建
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
