package com.example.seckill.mq;

import com.example.seckill.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SeckillMessageProducer {

    private final RabbitTemplate rabbitTemplate;

    public SeckillMessageProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发送秒杀成功消息，由消费端异步落库。
     * 注意：这里只做发送，不等待落库结果，接口 RT 因此保持极低。
     */
    public void send(SeckillMessage message) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.SECKILL_EXCHANGE,
                    RabbitMQConfig.SECKILL_ROUTING_KEY,
                    message);
            log.info("秒杀消息已投递: userId={}, goodsId={}", message.getUserId(), message.getGoodsId());
        } catch (Exception e) {
            // 消息发送失败属于严重问题：Redis 已经扣了库存但订单不会落库
            log.error("秒杀消息投递失败: {}", message, e);
            throw e;
        }
    }
}
