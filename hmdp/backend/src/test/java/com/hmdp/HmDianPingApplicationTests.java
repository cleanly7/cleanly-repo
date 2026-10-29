package com.hmdp;


import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private IShopService shopService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    //@Test
    void loadShopLocations(){
        //1.查询店铺信息
        List<Shop> list = shopService.query().list();
        //2.根据typeid分组
        Map<Long, List<Shop>> shopMap = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.新建 GeoLocations<String> 列表
        for (Map.Entry<Long, List<Shop>> shopEntry : shopMap.entrySet()) {
            Long typeId = shopEntry.getKey();
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            List<RedisGeoCommands.GeoLocation<String>> geoLocationList = new ArrayList<>();
            List<Shop> shopList = shopEntry.getValue();
            for (Shop shop : shopList) {
                geoLocationList.add(new RedisGeoCommands.GeoLocation<>(
                         shop.getId().toString()
                        ,new org.springframework.data.geo.Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, geoLocationList);
        }
    }
}
