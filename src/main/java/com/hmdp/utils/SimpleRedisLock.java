package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author jitwxs
 * @date 2022年09月17日 22:07
 */
public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;

    private String name;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    
    private static  DefaultRedisScript<Long> script = null;
    
    static {
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("unlock.lua"));
        script.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name =  name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {

        //获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }

    //使用lua脚本，保证释放锁的原子性
    @Override
    public void unLock() {

        stringRedisTemplate.execute(script,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

    }
    /*
    * 这中方式是有缺陷的，当大量并发时，可能业务判断成功当前锁属于自己，准备释放锁的时候
    * 这个是jvm进行fullGC阻塞业务，可能导致属于自己的锁过期被释放掉，这个时候线程2,获取到锁
    * 执行业务，线程一释放锁，会将线程二的锁释放掉，这个时候线程三进来，又可以获取到锁，
    * 造成线程安全问题，原因是查找线程标识与释放锁的操作不是原子的
    * */
//    @Override
//    public void unLock() {
//
//        //获取线程标识
//        String threadId = ID_PREFIX + stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断当前锁是否是当前线程所拥有
//        if (threadId.equals(ID_PREFIX + Thread.currentThread().getId())){
//            //是释放这个锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//
//    }
}
