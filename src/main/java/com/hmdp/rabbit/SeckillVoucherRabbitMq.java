package com.hmdp.rabbit;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * @author jitwxs
 * @date 2022年09月24日 11:13
 */

@Configuration
public class SeckillVoucherRabbitMq {

    public final static String VOUCHER_ORDER_QUEUE = "voucher.order.queue";
    public final static String VOUCHER_ORDER_EXCHANGE = "voucher.order.exchange";
    public final static String VOUCHER_ORDER_ROUTING_KEY = "voucher.order.routing.key";

    //定义一个交换机
    @Bean("voucherOrderExchange")
    public Exchange voucherOrderExchange(){
        return ExchangeBuilder.directExchange(VOUCHER_ORDER_EXCHANGE).build();
    }

    //定义一个存放订单信息的队列
    @Bean
    public Queue voucherOrderQueue(){
        return new Queue(VOUCHER_ORDER_QUEUE, true, false, false);
    }

    @Bean
    public Binding bindSeckillVoucherOrder(@Qualifier("voucherOrderExchange") Exchange exchange,
                                           @Qualifier("voucherOrderQueue") Queue queue){
        return BindingBuilder.bind(queue).to(exchange).with(VOUCHER_ORDER_ROUTING_KEY).noargs();
    }
}
