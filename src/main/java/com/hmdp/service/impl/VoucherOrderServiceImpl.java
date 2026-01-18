package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ILock;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ISeckillVoucherService seckillVoucherService;// 注入秒杀优惠券服务

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还没开始");
        }
        //判断是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }

        //判断库存是或否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
//        synchronized(userId.toString().intern()){//将 该方法上锁 确保spring事务生效之后同步锁才被释放
            /*这段代码用 synchronized(userId.toString().intern()) 做*按用户维度*的互斥：
*同一用户*的并发请求会竞争同一把锁，保证 查是否已下单 \-> 扣库存 \-> 写订单 这段逻辑串行执行，从而避免同一用户重复下单。
只在*单 JVM*内有效；多实例部署时不同机器/进程之间不会互斥，需要分布式锁或数据库唯一约束兜底。
intern() 用来确保相同 userId 得到的是*同一个锁对象*；但大量不同 userId 可能增加常量池压力、锁对象生命周期更长*/
        SimpleRedisLock lock=new SimpleRedisLock("order:"+userId,stringRedisTemplate);//创建锁对象
        boolean isLock = lock.tryLock(5000);//尝试加锁，设置锁超时时间为5秒
        if(!isLock){
            //获取锁失败 返回错误或者重试
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();//这行代码用于获取当前正在执行该方法的 Spring AOP 代理对象，并把它转换成 IVoucherOrderService 接口类型
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();//无论如何必须 释放锁
        }
    //}
    }
    @Transactional
    public  Result createVoucherOrder(Long voucherId){  //一人一单
        Long userId= UserHolder.getUser().getId();

        int count=query().eq("user_id",userId).eq("voucher_id",voucherId).count();
        if (count>0){
            return Result.fail("用户已经购买过一次");
        }
        //扣减库存
        boolean success=seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id",voucherId).gt("stock",0)
                .update();
        if(!success){
            return Result.fail("库存不足");
        }
        //创建订单
        VoucherOrder voucherOrder=new VoucherOrder();
        //订单id
        long orderId= redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(voucherId);
    }
}
