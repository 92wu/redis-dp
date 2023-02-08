package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    //静态代码块初始化
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        //设置脚本位置
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //设置返回值
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct //在当前类初始化完成以后开始执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //消息队列
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1.获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3.解析消息中的订单信息,此时明确知道只有一个消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    //取出键值对集合
                    Map<Object, Object> values = record.getValue();
                    //转成order对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4.创建订单
                    handleVoucherOrder(voucherOrder);

                    // 5.ACK确认 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1  STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1.获取失败，说明没有消息，结束循环
                        break;
                    }
                    // 3.解析消息中的订单信息,此时明确知道只有一个消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    //取出键值对集合
                    Map<Object, Object> values = record.getValue();
                    //转成order对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4.创建订单
                    handleVoucherOrder(voucherOrder);

                    // 5.ACK确认 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list异常", e);
                    //如果觉得循环太频繁，可以休眠
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

     

   /* //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 用于线程池处理的任务
    // 当初始化完毕后，就会去从对列中去拿信息
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象(新增代码)
        //利用redission获取锁（可重入），指定锁的名称
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁对象
        boolean isLock = lock.tryLock();
        //加锁失败
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            //因为此时这是个子线程，子线程不能获取代理对象，所以提前在seckillVoucher中获取对象
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        //2. 判断是否为0
        //long型转成int，方便比较
        int r = result.intValue();
        if (r != 0) {
            //2.1 不为0，没有购买资格，返回异常信息
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //拿到事务的代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //3. 返回订单id
        return Result.ok(0);
    }

   /* @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //2. 判断是否为0
        //long型转成int，方便比较
        int r = result.intValue();
        if (r != 0) {
            //2.1 不为0，没有购买资格，返回异常信息
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 为0，有购买资格，将优惠券id，用户id，订单id存入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.3 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.4 用户id
        voucherOrder.setUserId(userId);
        //2.5 代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.6 放入阻塞队列
        orderTasks.add(voucherOrder);
        //拿到事务的代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //3. 返回订单id
        return Result.ok(0);
    }*/

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1. 查询优惠劵
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始");
        }
        //3. 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            //已经结束
            return Result.fail("秒杀已经结束");
        }
        //4. 判断库存是否充足
        if (voucher.getStock() < 1){
            return Result.fail("已经抢光啦~");
        }
        Long userId = UserHolder.getUser().getId();
        //创建锁对象(新增代码)
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //利用redission获取锁（可重入），指定锁的名称
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁对象
        boolean isLock = lock.tryLock();
        //加锁失败
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            //拿到事务的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }
    }*/
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5. 一人一单
        Long userId = voucherOrder.getUserId();


        //5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //5.2 判断是否存在
        if (count > 0) {
            //已经买过一单了
            log.error("不能重复购买");
            return;
        }

        //6. 充足，扣减库存,手写sql
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足~");
            return;
        }


        //保存到数据库,创建订单
        save(voucherOrder);


    }
}


