package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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
    public Result queryTypeList() {
        //查redis
        String JsonList = stringRedisTemplate.opsForValue().get("cache:list:data");
        if(StrUtil.isNotBlank(JsonList)){
            //存在，直接返回
            List<ShopType> shopTypeList = JSONUtil.toList(JsonList, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //不存在，查询数据库
        List<ShopType> shopTypeList=query().orderByAsc("sort").list();
        if(shopTypeList==null||shopTypeList.size()==0){
            //数据库中不存在，返回错误
            return Result.fail("店铺类型不存在");
        }
        //数据库中存在，写入redis
        stringRedisTemplate.opsForValue().set("cache:list:data",JSONUtil.toJsonStr(shopTypeList));
        //返回店铺类型信息
        return Result.ok(shopTypeList);
    }
}
