package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithMutex(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        if (shopJson != null){
            return null;
        }

        String lock = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lock);
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            shop = getById(id);
            if (shop == null){
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lock);
        }
        return shop;
    }

    private static final ExecutorService CHAHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return shop;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (!isLock){
            CHAHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            unLock(lockKey);
        }
        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        if (shopJson != null){
            return null;
        }

        Shop shop = getById(id);
        if (shop == null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result saveShop(Shop shop) {
        boolean saved = save(shop);
        if (!saved || shop.getId() == null) {
            return Result.fail("新增店铺失败");
        }
        syncShopToRedis(shop);
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }

        Shop oldShop = getById(id);
        if (oldShop == null) {
            return Result.fail("店铺不存在");
        }

        updateById(shop);
        Shop latestShop = getById(id);
        removeShopGeo(oldShop);
        syncShopToRedis(latestShop);
        return Result.ok();
    }

    private void syncShopToRedis(Shop shop) {
        if (shop == null || shop.getId() == null) {
            return;
        }
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_KEY + shop.getId(),
                JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        addShopGeo(shop);
    }

    private void addShopGeo(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getTypeId() == null || shop.getX() == null || shop.getY() == null) {
            return;
        }
        stringRedisTemplate.opsForGeo().add(
                SHOP_GEO_KEY + shop.getTypeId(),
                new Point(shop.getX(), shop.getY()),
                shop.getId().toString()
        );
    }

    private void removeShopGeo(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getTypeId() == null) {
            return;
        }
        stringRedisTemplate.opsForGeo().remove(
                SHOP_GEO_KEY + shop.getTypeId(),
                shop.getId().toString()
        );
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null){
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            List<Shop> records = page.getRecords();
            ensureShopListInRedis(records);
            return Result.ok(records);
        }
        warmTypeShopsToRedis(typeId);
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(10000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        ensureShopListInRedis(shops);
        return Result.ok(shops);
    }

    @Override
    public Result queryShopByName(String name, Integer current) {
        Page<Shop> page = query()
                .like("name", name)
                .page(new Page<>(current, 10));
        List<Shop> records = page.getRecords();
        ensureShopListInRedis(records);
        return Result.ok(records);
    }

    private void warmTypeShopsToRedis(Integer typeId) {
        List<Shop> shops = query().eq("type_id", typeId).list();
        ensureShopListInRedis(shops);
    }

    private void ensureShopListInRedis(List<Shop> shops) {
        if (shops == null || shops.isEmpty()) {
            return;
        }
        for (Shop shop : shops) {
            ensureShopInRedis(shop);
        }
    }

    private void ensureShopInRedis(Shop shop) {
        if (shop == null || shop.getId() == null) {
            return;
        }
        stringRedisTemplate.opsForValue().setIfAbsent(
                CACHE_SHOP_KEY + shop.getId(),
                JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        addShopGeo(shop);
    }
}
