package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest
class HmDianPingApplicationTests {


    @Autowired
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisWorker redisWorker;

    @Autowired
    private UserServiceImpl userService;


    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void saveShopRedis() throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(300);
        Runnable runnable = () ->{

            for (int i = 0; i < 100; i++) {
                System.out.println(redisWorker.nextId("order"));
            }
            latch.countDown();
        };
        long begin = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);

        for (int i = 0; i < 300; i++) {
            es.submit(runnable);
        }
        latch.await();
        long end = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        System.out.println(end - begin);
    }

    //登录一千个账号,将token写入redis
    @Test
    public void loginTest() throws IOException {

        List<User> list = userService.query().list();
        FileWriter out = new FileWriter("D:\\code\\hm-dianping\\hm-dianping\\src\\main\\resources\\token.txt");
        BufferedWriter buffer = new BufferedWriter(out);
        //登录全部用户
        for (User user : list) {
            //生成随机token
            String token = UUID.randomUUID().toString(true);
            String tokenKey = LOGIN_USER_KEY + token;
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)   //设置忽略空的值
                            .setFieldValueEditor((filedName, filedValue) -> filedValue.toString()));//修改字段值，修改value
            //保存用户,到redis
            stringRedisTemplate.opsForHash().putAll(tokenKey , userMap);
            token = token + "\r\n";
            buffer.write(token);
            buffer.flush();
        }
        buffer.close();
        out.close();
    }

    //将所有店铺进行缓存预热
    @Test
    public void saveShopToRedis() throws InterruptedException {
        List<Long> ids = shopService.query().list().stream().map(shop -> shop.getId()).collect(Collectors.toList());
        for (Long id : ids) {
            cacheClient.saveObjectToRedis(CACHE_SHOP_KEY, id, CACHE_SHOP_TTL, Shop.class, shopService::getById);
        }

    }


    @Test
    void loadShopData() {
        // 1.查询店铺信息
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }
}
