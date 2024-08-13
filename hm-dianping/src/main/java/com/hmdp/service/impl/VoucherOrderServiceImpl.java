package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newFixedThreadPool(8);


    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        System.out.println("进入了阻塞订单handle阶段==================================");
        //获取用户
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("order"+userId);
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if(!isLock){
            //获取锁失败,返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    System.out.println("进入了阻塞订单阶段==================================");
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                   //创建订单
                    handleVoucherOrder(voucherOrder);
                }catch (InterruptedException e){
                    log.error("处理订单异常");
                }
            }
        }


    }

//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//        //是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        //判断库存是否充足
//        if(voucher.getStock()<1){
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order"+userId,stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order"+userId);
//        boolean isLock = lock.tryLock();
//        //判断是否获取锁成功
//        if(!isLock){
//            //获取锁失败,返回错误或重试
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//
//    }



    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private IVoucherOrderService proxy;
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        System.out.println("userId = " + userId);
        System.out.println("voucherId = " + voucherId);
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
                );
        //判断结果是否为零
        assert result != null;
        int r = result.intValue();
        if(r!=0){
            return switch (r) {
                case 1 -> Result.fail("库存不足");
                case 2 -> Result.fail("不能重复下单");
                default -> Result.fail("系统错误");
            };
        }
        long orderId = redisIdWorker.nextId("order");
        // 保存阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //加入阻塞队列
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //不为0

        //为0 有购买资格 把下单信息保存到阻塞队列

        //返回订单id
        return Result.ok(orderId);
    }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        System.out.println("进入了创建订单阶段==================================");
        //一人一单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
            //查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            System.out.println("user_id = " + userId);
            System.out.println("voucher_id = " + voucherId);
            System.out.println("count = " + count);
            if(count>0){
                //用户已经购买过了
                log.error("您已经购买过一次了");
                return;
            }
            //扣除库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock=stock-1")//set stock = stock-1
                    .eq("voucher_id", voucherId).gt("stock", 0)//where id = ? and stock > 0
                    .update();//
            if(!success){
                log.error("库存不足");
                return;
            }
            save(voucherOrder);
    }
}
