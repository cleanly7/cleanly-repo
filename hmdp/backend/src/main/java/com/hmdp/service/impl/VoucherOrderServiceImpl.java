package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    private final static DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建阻塞队列
    //public static BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024 * 1024);

    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /*//创建线程任务
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
        while (true) {
                try {
                    //1.取出阻塞队列数据
                    VoucherOrder voucherOrder = blockingQueue.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }*/
    private static final String QUEUE_NAME = "stream.orders";

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取订单,xreadgroup GROUP g1 c1 count 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    //2.获取失败 -> 进入下次循环继续读取
                    if (records == null || records.isEmpty()){
                        continue;
                    }
                    //解析数据
                    MapRecord<String, Object, Object> record = records.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    //3.获取成功,下单
                    getVoucherOrder(voucherOrder);
                    //4.ACK确认订单 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
            }
        }

    private void handlePendingList()  {
        while (true) {
            try {
                //1.获取pending_list订单,xreadgroup GROUP g1 c1 count 1 BLOCK 2000 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                );
                //2.获取失败 -> pending_list 处理完毕 跳出
                if (records == null || records.isEmpty()) {
                    break;
                }
                //解析数据
                MapRecord<String, Object, Object> record = records.get(0);
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                //3.获取成功,下单
                getVoucherOrder(voucherOrder);
                //4.ACK确认订单 SACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());
            } catch (Exception e) {
                log.error("处理pending_list订单异常", e);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
            //1. 获取用户
            Long userId = voucherOrder.getUserId();
            SimpleRedisLock lock = new SimpleRedisLock("lock:order:" + voucherOrder.getId(), stringRedisTemplate);
            boolean isLocked = lock.tryLock(10, TimeUnit.SECONDS);
            if (!isLocked) {
                log.error("请勿重复下单");
                return;
            }
            try {
                //5. 使用代理对象，由于这里是另外一个线程，
                proxy.getVoucherOrder(voucherOrder);
            } finally {
                lock.unlock();
            }
        }


    /**
     * 自己注入自己的代理对象（@Lazy 避免循环依赖），用于在同一类的内部方法调用中走事务
     */
    @Lazy
    @Autowired
    private IVoucherOrderService proxy;
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("Order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString()
        );
        //2.判断返回结果
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "请勿重复下单");
        }
/*        //3.创建订单 并 放入阻塞 队列
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //3.1订单号
        voucherOrder.setId(orderId);
        //3.2用户id
        voucherOrder.setUserId(userId);
        //3.3优惠券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        blockingQueue.add(voucherOrder);*/
        //4.返回订单id（订单由后台线程从消息队列异步创建）
        return Result.ok(orderId);
    }

/*
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if (now.isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀已经结束");
        }

        //4.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long id = UserHolder.getUser().getId();

        SimpleRedisLock lock = new SimpleRedisLock("lock:order:" + id, stringRedisTemplate);
        boolean isLocked = lock.tryLock(10, TimeUnit.SECONDS);
        if (!isLocked) {
            return Result.fail("请勿重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.getVoucherOrder(seckillVoucher);
        } finally {
            lock.unlock();
        }
    }
*/

    @Transactional
    public void getVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单逻辑
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        synchronized (userId.toString().intern()) {
            int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
            if (count > 0) {
                log.error("你已经抢过优惠券了哦");
                return;
            }
            //5. 扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            if (!success) {
                log.error("库存不足");
            }
            //7. 将订单数据保存到表中
            save(voucherOrder);

        }
    }
}
