package com.hmdp.service.impl;

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
import com.hmdp.entity.RedisData;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    @Autowired
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);

        //利用缓存工具类 解决缓存击穿问题
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::queryWithPassThrough, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("店铺不存在！！");
        }
        return Result.ok(shop);
    }
    private final static ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private Shop queryWithLogicalExpire(Long id){
        //1.通过redis查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.缓存未命中 -> 返回空
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //3.缓存命中 -> 判断数据是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);

        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data.toString(), Shop.class);

        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            //未过期 -> 返回数据
            return shop;
        }
        //4.过期 —> 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if(tryLock(lockKey)) {
            //5.获取锁成功 -> 新开线程 更新数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //1.double check
                String nowShopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
                RedisData nowRedisData = JSONUtil.toBean(nowShopJson, RedisData.class);
                LocalDateTime nowExpireTime = nowRedisData.getExpireTime();
                if(nowExpireTime.isAfter(LocalDateTime.now())){
                    //未过期 -> 返回数据
                    return;
                }
                //2.更新redisData
                saveRedisData(id, 30L);
            });
        }
        //主线程 or 获取锁失败 返回旧数据
        return shop;
    }
    private Shop queryWithMutex(Long id){
        //1.通过redis查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.查到了，返回
        if(shopJson != null){
            // 1. 先判断字符串是否有实际内容
            if (StrUtil.isNotBlank(shopJson)) {
                // 2. 只有在字符串非空时，才进行转换
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            return null;
        }
        //3.未查到，尝试获取锁
        Shop shop = null;
        String key = "lock:shop:" + id;
        try {
            if(!tryLock(key)){
                //获取失败 -> 休眠一段时间再获取
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return queryWithMutex(id);
            }
            //获取锁成功，查询数据库
            shop = getById(id);
            if(shop == null){
                //不存在，返回空并创建空缓存
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //4.缓存数据设置ttl —> 返回 释放锁
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(key);
        }
        return shop;

    }
    private Shop queryWithPassThrough(Long id){
        //1.通过redis查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.存在直接返回（空串是“店铺不存在”的占位符，不能反序列化成空对象）
        if(shopJson != null){
            if (StrUtil.isBlank(shopJson)) {
                return null;
            }
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //3.不存在，通过mysql查询
        Shop shop = getById(id);
        if(shop == null){
            //不存在，返回错误  ————》 导致缓存穿透
            //return Result.fail("店铺不存在");

            //这里的常量值是2分钟
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //4.存在 -》 返回 and 缓存数据设置ttl
        String JsonStr = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JsonStr,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    public void saveRedisData(Long id, Long expiredSeconds){
        //1.MySQL查询
        Shop shop = getById(id);
        //2.封装对象
        RedisData shopData = new RedisData();
        shopData.setData(shop);
        shopData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSeconds));
        //3.缓存数据设置ttl
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shopData));
    }
    public Result update(Shop shop) {
        //首先先判一下空
        if (shop.getId() == null){
            return Result.fail("店铺id不能为空！！");
        }
        //先更新数据库
        updateById(shop);
        //再更新redis —— 必须和 queryById 用同一种格式（逻辑过期），否则详情页会读不出来
        cacheClient.setWithLogicExpire(CACHE_SHOP_KEY + shop.getId(), shop, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok();
    }

    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否根据坐标查
        if(x == null || y == null){
            // 不需要根据坐标查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords(), page.getTotal());
        }
        //2.获取分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = from + SystemConstants.DEFAULT_PAGE_SIZE;
        //3.从redis 中查询数据 : shopid , point
        String key = "shop:geo:" + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> search = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (search == null) {
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = search.getContent();
        // 数据不足 when from 已经超出范围，直接返回空
        if (list.size() <= from) {
            return Result.ok(java.util.Collections.emptyList(), 0L);
        }
        //截取from ~ end（end 不能超过实际大小）
        list = list.subList(from, Math.min(end, list.size()));
        List<Shop> shops = list.stream().map(result -> {
            String shopId = result.getContent().getName();
            Shop shop = getById(shopId);
            shop.setDistance(result.getDistance().getValue());
            return shop;
        }).collect(Collectors.toList());

        //5.返回结果
        return Result.ok(shops, (long) list.size());
    }
}
