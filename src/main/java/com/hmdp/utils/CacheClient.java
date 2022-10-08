package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author jitwxs
 * @date 2022年09月17日 7:31
 */

@Component
public class CacheClient {


    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            new ThreadPoolExecutor(5,
                    10,
                    100,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>());

    public void set(String cacheKey, Object value, Long expire, TimeUnit unit){

         stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(value), expire, unit);
    }

    public void setLogical(String cacheKey, Object value, Long expire, TimeUnit unit){

        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expire)));

        stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,Long expire, TimeUnit unit){
        //1.获取缓存中的数据
        String cacheKey = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(cacheKey);

        //2.判断有没有缓存数据，没有从数据库中获取
        if (StrUtil.isNotBlank(json)){
            //不为空,直接返回

            return JSONUtil.toBean(json, type);
        }

        if (json != null){      //shopJSon为空字符串,说明这个店铺不存在
            return null;
        }
        //3.不存在，查数据库
        R r = dbFallBack.apply(id);
        if(r == null){
            //将空值缓存到redis中
            set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //4.结果加入缓存
        set(cacheKey, r, expire, unit);

        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String cacheKeyPrefix, String lockKeyPrefix,
            ID id, Class<R> type,Function<ID, R> dbFallBack,Long expire, TimeUnit unit){
        //1.获取缓存中的数据
        String cacheKey = cacheKeyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(cacheKey);

        //2.判断有没有缓存数据
        if (StrUtil.isBlank(json)){
            //直接返回,说明不存在这个热点数据
            return null;
        }

        // 3.缓存命中，反序列化json
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否逻辑过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //4.1 未过期，直接返回数据
            return r;
        }

        //4.2 过期，进行缓存重建
        //5 缓存重建
        //5.1 获取互斥锁
        String locKey = lockKeyPrefix + id;
        boolean isLocke = tryLock(locKey);
        //5.2 判断互斥锁是否成功
        if (isLocke){
            //5.4 成功， 开启独立线程，进行缓存重建
            //获取到锁以后再一次检查，shop是否过期
            json = stringRedisTemplate.opsForValue().get(cacheKey);
            redisData = JSONUtil.toBean(json, RedisData.class);
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
                //没有过期直接返回shop，并释放锁
                unLock(locKey);
                return JSONUtil.toBean((JSONObject) redisData.getData(), type);
            }
            //shop还是过期，进行缓存重建
            CacheClient.CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallBack.apply(id);
                    setLogical(cacheKey, r1, expire, unit);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    //释放锁
                    unLock(locKey);
                }
            });
        }
        //5.3 失败，返回过期的店铺信息或者开启独立线程后，返回旧的数据
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    public <R, ID> void saveObjectToRedis(String cacheKey,ID id, Long expireTime, Class<R> type,Function<ID, R> dbFallBack) throws InterruptedException {

        //查询数据库
         R r = dbFallBack.apply(id);
        //2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        redisData.setData(r);
        TimeUnit.MILLISECONDS.sleep(200);
        //3.存入redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }
}
