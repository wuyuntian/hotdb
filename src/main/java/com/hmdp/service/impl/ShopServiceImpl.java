package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {

//        解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(
//                CACHE_SHOP_KEY,id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //互斥锁 解决缓存击穿
//        Shop shop = queryWithMute(id);
        //逻辑过期 解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS
        );

        if (shop == null) {
            //不是热点数据，查询数据库
            shop = getById(id);
        }

        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateShop(Shop shop) {

        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }

    /**
     * 根据用户的位置信息，显示附近商铺功能
     * @param typeId 商户类型
     * @param current 页码，滚动查询
     * @param x 经度
     * @param y 纬度
     * @return 放回符合要去的商户集合
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        //1.判断是否需要根据坐标查询
        if(x == null || y== null){
            //查询数据库
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<Shop>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        //2. 1.计算分页参数
        int form = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3.查询redis，根据距离排序、分页，结果：shopID distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));

        if (results == null){
            //没有匹配结果
            return Result.ok();
        }
        //4. 解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (form >= list.size()){
            //进行分页后，当前页没有数据直接返回
            return Result.ok();
        }
        //分页，截取form-end部分，lists的数据是从0到end，只需跳过end
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(form).forEach(result -> {
            String shopIdStr = result.getContent().toString();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });
        //5.根据获得的id查询店铺
        String idStr =  StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("order by FIELD(id, " + idStr + ")").list();
        return Result.ok(shops);
    }

/*    public Shop queryWithLogicalExpire(Long id){
        //1.获取缓存中的数据
        String cacheKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);

        //2.判断有没有缓存数据
        if (StrUtil.isBlank(shopJson)){
            //直接返回,说明不存在这个热点数据
            return null;
        }

        // 3.缓存命中，反序列化json
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否逻辑过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //4.1 未过期，直接返回数据
            return shop;
        }

        //4.2 过期，进行缓存重建
        //5 缓存重建
        //5.1 获取互斥锁
        String locKey = LOCK_SHOP_KEY + id;
        boolean isLocke = tryLock(locKey);
        //5.2 判断互斥锁是否成功
        if (isLocke){
            //5.4 成功， 开启独立线程，进行缓存重建
            //获取到锁以后再一次检查，shop是否过期
            shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
                //没有过期直接返回shop，并释放锁
                unLock(locKey);
                return JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            }
            //shop还是过期，进行缓存重建
            ShopServiceImpl.CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShopToRedis(id, 30L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    //释放锁
                    unLock(locKey);
                }
            });
        }
        //5.3 失败，返回过期的店铺信息或者开启独立线程后，返回旧的数据
        return shop;
    }

    public Shop queryWithMute(Long id) {
        //1.获取缓存中的数据
        String cacheKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);

        //2.判断有没有缓存数据，没有从数据库中获取
        if (StrUtil.isNotBlank(shopJson)){
            //不为空,直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        if (shopJson != null){      //shopJSon为空字符串,说明这个店铺不存在
            return null;
        }

        //3缓存重建
        //3.1获取锁，判断是否获取成功
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock){
                //3.3 获取锁失败，休眠递归再次尝试获取锁
                TimeUnit.MICROSECONDS.sleep(100);
                return queryWithMute(id);
            }else {
                //获取锁成功，判断redis中是否有缓存有直接返回
                shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(shopJson)){
                    //不为空,直接返回
                    shop = JSONUtil.toBean(shopJson, Shop.class);
                    return shop;
                }
            }
            //4.不存在，查数据库
            shop = getById(id);
            //模拟重建延迟
            TimeUnit.MILLISECONDS.sleep(200);
            if(shop == null){
                //将空值缓存到redis中
                stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //5.结果加入缓存
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            log.error("thread sleep err .......");
            e.printStackTrace();
        } finally {
            //6.释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        //1.获取缓存中的数据
        String cacheKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);

        //2.判断有没有缓存数据，没有从数据库中获取
        if (StrUtil.isNotBlank(shopJson)){
            //不为空,直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        if (shopJson != null){      //shopJSon为空字符串,说明这个店铺不存在
            return null;
        }
        //3.不存在，查数据库
        Shop shop = getById(id);
        if(shop == null){
            //将空值缓存到redis中
            stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //4.结果加入缓存
        stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShopToRedis(Long id, Long expireTime) throws InterruptedException {

        //查询数据库
        Shop shop = getById(id);
        //2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        redisData.setData(shop);
        TimeUnit.MILLISECONDS.sleep(200);
        //3.存入redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/
}
