package com.hmdp.utils;

import com.fasterxml.jackson.core.format.DataFormatDetector;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author jitwxs
 * @date 2022年09月17日 10:52
 */

@Component
public class RedisWorker {

    private static final long BEGIN_TIMESTAMP = 1663372800;

    private static final long COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //生成唯一自增id 时间戳加上序列号
    public long nextId(String keyPrefix){

        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //生成序列号
        //获取当前时间
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //自增长
        Long increment = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        return timestamp << COUNT_BITS | increment;
    }
}
