package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import static com.hmdp.rabbit.SeckillVoucherRabbitMq.VOUCHER_ORDER_QUEUE;

/**
 * @author jitwxs
 * @date 2022年09月24日 10:41
 */
@Service
@Slf4j
public class CreateOrderRabbitListener {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @RabbitListener(queues = VOUCHER_ORDER_QUEUE)
    public void createOrder(Message message, Channel channel) throws IOException {

        try {
            String orderStr = new String(message.getBody(), "utf-8");
            //接收消息
            VoucherOrder voucherOrder = JSONUtil.toBean(orderStr, VoucherOrder.class);
            //写入数据库
            voucherOrderService.handleVoucherOrder(voucherOrder);
            //确认接收消息
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            //其中步骤出现了问题，不确认接收消息
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        }
    }
}
