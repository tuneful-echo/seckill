package com.example.seckill.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置：直连交换机 + 持久化队列
 *
 * 作用：Redis 扣减成功后立即返回，把"落库"这件慢事丢进队列异步做，
 * 接口 RT 从几十毫秒降到几毫秒，同时用队列缓冲住流量洪峰。
 */
@Configuration
public class RabbitMQConfig {

    public static final String SECKILL_EXCHANGE = "seckill.exchange";
    public static final String SECKILL_QUEUE = "seckill.queue";
    public static final String SECKILL_ROUTING_KEY = "seckill.routing";

    @Bean
    public DirectExchange seckillExchange() {
        // durable=true，RabbitMQ 重启后交换机仍在
        return new DirectExchange(SECKILL_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillQueue() {
        // durable=true，队列持久化
        return new Queue(SECKILL_QUEUE, true);
    }

    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue())
                .to(seckillExchange())
                .with(SECKILL_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
