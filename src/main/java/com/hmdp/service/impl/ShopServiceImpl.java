package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) throws InterruptedException {
    return Result.ok(queryShopByIdMutex(id));
    }
    private static final ExecutorService CACHE_REBUBLD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryShopByIdWithLogicalExpire(Long id) throws InterruptedException {
        //从redis中查询商品
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        if(StrUtil.isBlank(shopJson)){
            //不存在
            return null;
        }
        //命中需要json反序列化成对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data= (JSONObject) redisData.getData();
        Shop shop=JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){//表示过期时间在当前时间之后
            //未过期，直接返回店铺信息
            return shop;
        }
        //获取互斥锁
        String lockKey="lock:shop:"+id;
        boolean isLock=tryLock(lockKey);//锁是否获取成功
       if(isLock){//过期了，需要缓存重建 且拿到了锁
           CACHE_REBUBLD_EXECUTOR.submit(()->{
               try {
                   //重建缓存
                   saveShop2Redis(id,20L);
               } catch (InterruptedException e) {
                   throw new RuntimeException(e);
               }finally {
                   //释放锁
                   unlock(lockKey);
               }
           });}

        //不存在，根据id查询数据库
        Shop shop = getById(id);
        Thread.sleep(200);//模拟重建缓存的延时
        if(shop==null){
            //缓存空值，防止缓存穿透
            stringRedisTemplate.opsForValue().set("cache:shop:"+id,"",2L,java.util.concurrent.TimeUnit.MINUTES);
            //数据库中不存在，返回错误
            return null;
        }
        //命中的是否是空值
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //数据库中存在，写入redis
        saveShop2Redis(id,20L);
        //释放锁
        unlock(lockKey);
        //返回店铺信息
        return shop;
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);//模拟重建缓存的延时
        //2.封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set("cache:shop:"+id, JSONUtil.toJsonStr(redisData));
    }
    //互斥锁解决缓存击穿
    public Shop queryShopByIdMutex(Long id) throws InterruptedException {
        //从redis中查询商品
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        if(StrUtil.isNotBlank(shopJson)){
            //存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //获取互斥锁
        String lockKey="lock:shop:"+id;
        boolean isLock=tryLock(lockKey);//锁是否获取成功
        if(!isLock){
            Thread.sleep(50);
            return queryShopByIdMutex(id);
        }
        //不存在，根据id查询数据库
        Shop shop = getById(id);
        Thread.sleep(200);//模拟重建缓存的延时
        if(shop==null){
            //缓存空值，防止缓存穿透
            stringRedisTemplate.opsForValue().set("cache:shop:"+id,"",2L,java.util.concurrent.TimeUnit.MINUTES);
            //数据库中不存在，返回错误
            return null;
        }
        //命中的是否是空值
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //数据库中存在，写入redis
        stringRedisTemplate.opsForValue().set("cache:shop:"+id,JSONUtil.toJsonStr(shop),30L,java.util.concurrent.TimeUnit.MINUTES);
        //释放锁
        unlock(lockKey);
        //返回店铺信息
        return shop;
    }
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, java.util.concurrent.TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    @Override
    public Result update(Shop shop) {
        Long id=shop.getId();
        if(id==null){
            return Result.fail("店铺id不存在");
        }
        //更新数据库
        updateById(shop);//mybatis-plus方法，根据id更新 默认读取主键字段
        //删除缓存
        stringRedisTemplate.delete("cache:shop:"+id);
        return Result.ok();
    }
}
