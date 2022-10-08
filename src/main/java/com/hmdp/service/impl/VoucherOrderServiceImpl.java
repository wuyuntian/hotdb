package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import jdk.nashorn.internal.objects.annotations.Constructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import java.util.concurrent.*;

import static com.hmdp.rabbit.SeckillVoucherRabbitMq.*;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private RedisWorker redisWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService iVoucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    private volatile IVoucherOrderService proxy;

    private static DefaultRedisScript<Long> SECKILL_SCRIPT = null;

    private static final BlockingQueue<VoucherOrder> ORDERTASKS = new ArrayBlockingQueue(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建一个初始化前的业务，让bean在spring初始化前就执行这个异步任务
//    @PostConstruct
//    public void init(){
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }

//    private class VoucherOrderHandler implements Runnable{
//
//        private final String queueName = "stream.orders";
//        @Override
//        public void run() {
//            HandleStreamMessage();
//        }
//        //使用redis的消息队列获得订单信息，完成异步任务
//        private void HandleStreamMessage(){
//            while(true){
//                try {
//                    //创建一个消费者组以及消费者，读取消息队列还未被消费的消息，获得订单信息
//                    //XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
//                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
//                    );
//                    if (records == null || records.isEmpty()){
//                        //获取失败，说明没有消息，继续下一次循环
//                        continue;
//                    }
//                    //每次读取一个消息
//                    MapRecord<String, Object, Object> record = records.get(0);
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), false);
//                    //创建一个订单
//                    handleVoucherOrder(voucherOrder);
//                    //订单创建成功，进行消息确认
//                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
//                }catch (Exception e){
//                    log.error("创建订单异常", e);
//                    e.printStackTrace();
//                    //对pending-list内的消息进行消费
//                    handlePendingList();
//                }
//            }
//        }
//        //获取消息队列中消费了但是没有确定的消息
//        private void handlePendingList() {
//
//            while(true){
//                try {
//                    //创建一个消费者组以及消费者，读取消息队列还未被消费的消息，获得订单信息
//                    //XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
//                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            StreamOffset.create(queueName, ReadOffset.from("0"))
//                    );
//                    if (records == null || records.isEmpty()){
//                        //获取失败，说明pending-list中没有消息了
//                        break;
//                    }
//                    //每次读取一个消息
//                    MapRecord<String, Object, Object> record = records.get(0);
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), false);
//                    //创建一个订单
//                    handleVoucherOrder(voucherOrder);
//                    //订单创建成功，进行消息确认
//                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
//                }catch (Exception e){
//                    log.error("获取pending-list异常", e);
//                    e.printStackTrace();
//                    try {
//                        TimeUnit.MILLISECONDS.sleep(10);
//                    } catch (InterruptedException ex) {
//                        ex.printStackTrace();
//                    }
//                }
//            }
//        }
//
//        //使用java的阻塞队列完成异步任务
//        private void handleBlockQueue(){
//            while(true){
//                try {
//                    //从阻塞队列中获取一个任务，执行
//                    VoucherOrder voucherOrder = (VoucherOrder) ORDERTASKS.take();
//                    //创建一个订单
//                    handleVoucherOrder(voucherOrder);
//                }catch (Exception e){
//                    log.error("创建订单异常", e);
//                }
//            }
//        }
//    }

    //rabbitMq完成异步任务
    @Override
    public Result seckillVoucher(Long voucherId) {

        //判断这个优惠券还有没有开始抢购
        String success = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
        if (StrUtil.isBlank(success)){
            return Result.fail("优惠券还未开始抢购或者已结束");
        }
        //获取用户Id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        Long orderId = redisWorker.nextId("voucher_order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        //判断结果是否为0
        int r = result.intValue();
        //不为0，判断结果是1，还是2
        if (r != 0){
            return Result.ok(r == 1 ? "库存不足": "一个用户只能购买一单");
        }
        //给rabbitmq中发布消息
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        String voucherOrderStr = JSONUtil.toJsonStr(voucherOrder);
        rabbitTemplate.convertAndSend(VOUCHER_ORDER_EXCHANGE, VOUCHER_ORDER_ROUTING_KEY, voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }
/*    //使用redis的消息队列完成异步任务
    @Override
    public Result seckillVoucher(Long voucherId) {

        //判断这个优惠券还有没有开始抢购
        String success = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
        if (StrUtil.isBlank(success)){
            return Result.fail("优惠券还未开始抢购或者已结束");
        }
        //获取用户Id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        Long orderId = redisWorker.nextId("voucher_order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        //判断结果是否为0
        int r = result.intValue();
        //不为0，判断结果是1，还是2
        if (r != 0){
            return Result.ok(r == 1 ? "库存不足": "一个用户只能购买一单");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }*/
    /*
    * 通过redis缓存优惠券的库存来解决超卖问题
    * 在redis定义set存储每一个优惠券的所有购买用户Id，解决一人一单问题
    * 这个方案还是存在问题
    * 因为阻塞对列是jvm中的有可能导致内存溢出，照成数据丢失
    * 我们无法确定队列中的任务是否一定会执行
    * 当阻塞队列满时，我们不知到任务会不会还没有执行完毕，就被丢弃
    * */
/*    @Override
    public Result seckillVoucher(Long voucherId) {

        //判断这个优惠券还有没有开始抢购
        String success = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
        if (StrUtil.isBlank(success)){
            return Result.fail("优惠券还未开始抢购或者已结束");
        }
        //获取用户Id
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //判断结果是否为0
        int r = result.intValue();
        //不为0，判断结果是1，还是2
        if (r != 0){
            return Result.ok(r == 1 ? "库存不足": "一个用户只能购买一单");
        }
        //为0，有购买资格，将订单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisWorker.nextId("voucher_order");
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        ORDERTASKS.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }*/

    //异步调用，根据阻塞队列中的voucherOrder，创建一个订单
    public void handleVoucherOrder(VoucherOrder voucherOrder){

        String lockKey = SECKILL_STOCK_KEY  + voucherOrder.getUserId()  + ":" + voucherOrder.getVoucherId();
        RLock lock = redissonClient.getLock(lockKey);
        //使用redisson分布式锁

        boolean isLock = lock.tryLock();
        if (!isLock){
            //没有获取到锁
            log.error("不允许重复下单");
            return;
        }

        try {
            //通过注入自身，防止事务失效
//            iVoucherOrderService.createVoucherOrder(voucherOrder.getVoucherId(), voucherOrder.getUserId());
            //如果是分布式的系统这里会出现问题
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
    /*
    * 通过mysql的update语句的行锁解决超卖问题
    * 同过查询数据的优惠券订单信息是否存在以及加锁，来处理一人一单
    * 与数据库交互次数高，效率低
    * */
/*    @Override
    public Result seckillVoucher(Long voucherId) {

        //1。查询优惠卷
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        if (voucher == null){

            return Result.fail("优惠券不存在");
        }

        //2. 判断秒杀是否开始
        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime().isAfter(now)){
            return Result.fail("秒杀还没有开始");
        }
        //3. 判断秒杀是否结束
        if(voucher.getEndTime().isBefore(now)){
            return Result.fail("秒杀已经结束");
        }

        //4. 判断库存是否充足
        if (voucher.getStock() < 1){
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
             //为每一个用户加上同步锁
        //获取SimpleRedisLock
        String lockKey = SECKILL_STOCK_KEY  + userId  + ":" + voucherId;
        //使用自定义redis分布式锁
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, lockKey);
        RLock lock = redissonClient.getLock(lockKey);
        //使用redisson分布式锁

        boolean isLock = lock.tryLock();
        if (!isLock){
            //没有获取到锁，根据业务要求，直接返回，一个用户只能抢购一张限时优惠券
            return Result.fail("一张限时优惠券,一个用户只能抢购一次");
        }
        //通过注入自身，防止事务失效
//            return iVoucherOrderService.createVoucherOrder(voucherId, userId);
        //通过获取当前对象的代理对象，调用代理对象方法防止事务失效
        //前提要导入aspectjweaver依赖，并在启动类加上注解@EnableAspectJAutoProxy(exposeProxy = true)暴露代理对象
        try {
            IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
            return voucherOrderService.createVoucherOrder(voucherId, userId);
        } finally {
            lock.unlock();
        }
    }*/


    @Transactional(rollbackFor = Exception.class)
    public Result createVoucherOrder(Long voucherId, Long userId) {

        //5. 一人一单
        //5.1 根据用户id查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0){
            //用户已经下过单了,直接返回
            log.error("用户已经购买了一次了");
            return Result.fail("每一个用户只能购买一次");
        }
        //6. 扣减库存
        boolean result = iSeckillVoucherService.update().
                setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!result){
            log.error("库存不足");
            return Result.fail("库存不足");
        }
        //7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1 获取订单id
        long voucherOrderId = redisWorker.nextId("voucher_order");
        voucherOrder.setId(voucherOrderId);
        //7.2 用户id
        voucherOrder.setUserId(userId);
        //7.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //7. 返回订单id;
        return Result.ok(voucherOrderId);
    }

    /*
    * 异步线程调用，创建订单，扣减对应优惠券的库存
    * */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //6. 扣减库存
        boolean result = iSeckillVoucherService.update().
                setSql("stock = stock - 1").eq("voucher_id", voucherOrder.getVoucherId()).
                gt("stock", 0).update();
        if (!result){
            log.error("库存不足");
            //库存不足直接返回，不创建订单
            return;
        }

        save(voucherOrder);
    }
}
