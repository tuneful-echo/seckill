package com.example.seckill.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.common.SeckillKeys;
import com.example.seckill.config.RabbitMQConfig;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.mapper.SeckillGoodsMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀消息消费端：异步落库
 *
 * 面试重点（这三条是"异步下单"必须能回答的追问）：
 * 1. 重复消费怎么办 -> Redis 结果键判重 + 数据库唯一索引 uk_user_goods 兜底
 * 2. 消费失败怎么办   -> 抛出异常交给 RabbitMQ 重试（本项目用默认自动 ack + 重试）
 * 3. 扣库存与插订单顺序 -> 先扣库存再插订单，插订单失败要补偿回补库存
 */
@Slf4j
@Component
public class SeckillMessageConsumer {

    private final SeckillGoodsMapper goodsMapper;
    private final SeckillOrderMapper orderMapper;
    private final StringRedisTemplate redis;

    public SeckillMessageConsumer(SeckillGoodsMapper goodsMapper,
                                  SeckillOrderMapper orderMapper,
                                  StringRedisTemplate redis) {
        this.goodsMapper = goodsMapper;
        this.orderMapper = orderMapper;
        this.redis = redis;
    }

    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE)
    public void consume(SeckillMessage message) {
        Long userId = message.getUserId();
        Long goodsId = message.getGoodsId();
        String resultKey = SeckillKeys.result(goodsId, userId);

        // 幂等第一道：结果键已是最终结果（订单ID / SOLD_OUT）才说明这条消息处理过了
        // 注意：投递消息前会先写 "QUEUING" 占位，不能把占位误判成"已处理"，否则消息永远被跳过、订单永不落库
        String cached = redis.opsForValue().get(resultKey);
        if (cached != null && !"QUEUING".equals(cached)) {
            log.info("重复消息，已跳过: userId={}, goodsId={}", userId, goodsId);
            return;
        }

        // 幂等第二道：库里已有该用户的订单，直接回写结果
        LambdaQueryWrapper<SeckillOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SeckillOrder::getUserId, userId).eq(SeckillOrder::getGoodsId, goodsId);
        SeckillOrder exists = orderMapper.selectOne(wrapper);
        if (exists != null) {
            redis.opsForValue().set(resultKey, String.valueOf(exists.getId()), 30, TimeUnit.MINUTES);
            return;
        }

        // 1. 数据库兜底扣减库存（WHERE stock > 0 保证不会扣成负数）
        int affected = goodsMapper.reduceStock(goodsId);
        if (affected == 0) {
            log.warn("数据库库存不足，秒杀失败: userId={}, goodsId={}", userId, goodsId);
            redis.opsForValue().set(resultKey, "SOLD_OUT", 30, TimeUnit.MINUTES);
            return;
        }

        // 2. 写入订单
        SeckillOrder order = buildOrder(userId, goodsId);
        try {
            orderMapper.insert(order);
            redis.opsForValue().set(resultKey, String.valueOf(order.getId()), 30, TimeUnit.MINUTES);
            log.info("订单创建成功: orderId={}, userId={}, goodsId={}", order.getId(), userId, goodsId);
        } catch (DuplicateKeyException e) {
            // 唯一索引 uk_user_goods 拦截：并发下同一用户产生了两条消息
            // 库存已经扣了但订单没建成，必须补偿回去，否则库存会少卖
            goodsMapper.incrementStock(goodsId);
            log.warn("唯一索引拦截重复下单，已回补库存: userId={}, goodsId={}", userId, goodsId);
            LambdaQueryWrapper<SeckillOrder> q = new LambdaQueryWrapper<>();
            q.eq(SeckillOrder::getUserId, userId).eq(SeckillOrder::getGoodsId, goodsId);
            SeckillOrder existed = orderMapper.selectOne(q);
            if (existed != null) {
                redis.opsForValue().set(resultKey, String.valueOf(existed.getId()), 30, TimeUnit.MINUTES);
            }
        }
    }

    private SeckillOrder buildOrder(Long userId, Long goodsId) {
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(buildOrderNo(userId));
        order.setUserId(userId);
        order.setGoodsId(goodsId);
        order.setStatus(0);
        order.setCreateTime(LocalDateTime.now());

        SeckillGoods goods = goodsMapper.selectById(goodsId);
        order.setGoodsPrice(goods == null ? new BigDecimal("0.01") : goods.getSeckillPrice());
        return order;
    }

    private String buildOrderNo(Long userId) {
        long ts = System.currentTimeMillis();
        int rand = ThreadLocalRandom.current().nextInt(1000, 9999);
        return ts + String.valueOf(userId % 1000) + rand;
    }
}
