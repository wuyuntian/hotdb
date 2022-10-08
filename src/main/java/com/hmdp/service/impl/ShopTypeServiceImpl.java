package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryList() {

        //1.从redis中获取缓存
        String shopTypeKey = CACHE_SHOP_TYPE_KEY;
        List<String> CacheShopTypeList = stringRedisTemplate.opsForList().range(shopTypeKey,0, -1);
        if (CacheShopTypeList != null && CacheShopTypeList.size() > 0){
            //不为空直接返回
            List<ShopType> shopTypeList = CacheShopTypeList.stream().map(item -> {
                return JSONUtil.toBean(item, ShopType.class);
            }).collect(Collectors.toList());
            return shopTypeList;
        }
        //为空从数据库中获取
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        CacheShopTypeList = shopTypeList.stream().map(item ->
                    JSONUtil.toJsonStr(item)
                ).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(shopTypeKey, CacheShopTypeList);
        stringRedisTemplate.expire(shopTypeKey, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        return shopTypeList;
    }
}
