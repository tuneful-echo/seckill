package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.common.BusinessException;
import com.example.seckill.common.ResultCode;
import com.example.seckill.common.SeckillKeys;
import com.example.seckill.config.SeckillProperties;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.mapper.SeckillGoodsMapper;
import com.example.seckill.mq.SeckillMessage;
import com.example.seckill.mq.SeckillMessageProducer;
import com.example.seckill.service.SeckillService;
import com.example.seckill.util.MD5Util;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SeckillServiceImpl implements SeckillService {

    private static final String QUEUING = "QUEUING";

    /**
     * 本地售罄标记。
     * 库存扣到 0 之后，后续请求连 Redis 都不用访问，直接在内存里返回售罄，
     * 这是压测时能把无效流量挡在最外层的关键。
     */
    private final Map<Long, Boolean> soldOutMap = new ConcurrentHashMap<>();

    /**
     * 商品起止时间本地缓存，避免秒杀链路里查数据库
     */
    private final Map<Long, long[]> timeMap = new ConcurrentHashMap<>();

    private final SeckillGoodsMapper goodsMapper;
    private final StringRedisTemplate redis;
    private final SeckillMessageProducer producer;
    private final SeckillProperties properties;
    private final DefaultRedisScript<Long> seckillScript;

    public SeckillServiceImpl(SeckillGoodsMapper goodsMapper,
                              StringRedisTemplate redis,
                              SeckillMessageProducer producer,
                              SeckillProperties properties) {
        this.goodsMapper = goodsMapper;
        this.redis = redis;
        this.producer = producer;
        this.properties = properties;

        this.seckillScript = new DefaultRedisScript<>();
        this.seckillScript.setLocation(new ClassPathResource("lua/seckill.lua"));
        this.seckillScript.setResultType(Long.class);
    }

    @Override
    public List<SeckillGoods> listGoods() {
        LambdaQueryWrapper<SeckillGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(SeckillGoods::getStartTime);
        return goodsMapper.selectList(wrapper);
    }

    @Override
    public SeckillGoods getGoods(Long goodsId) {
        SeckillGoods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            throw new BusinessException(ResultCode.FAILED, "商品不存在");
        }
        return goods;
    }

    @Override
    public String createPath(Long userId, Long goodsId) {
        // 加盐 MD5：同一用户同一商品每次生成的 path 相同，但外部脚本猜不出来
        String path = MD5Util.md5(properties.getSalt() + "/" + goodsId + "/" + userId);
        redis.opsForValue().set(SeckillKeys.path(goodsId, userId),
                path, properties.getPathTtlSeconds(), TimeUnit.SECONDS);
        return path;
    }

    @Override
    public int seckill(Long userId, Long goodsId, String path) {
        // 1. 时间校验（走内存，不查库）
        long[] time = timeMap.get(goodsId);
        if (time == null) {
            SeckillGoods goods = goodsMapper.selectById(goodsId);
            if (goods == null) {
                throw new BusinessException(ResultCode.FAILED, "商品不存在");
            }
            time = new long[]{toMillis(goods.getStartTime(), 0L), toMillis(goods.getEndTime(), Long.MAX_VALUE)};
            timeMap.put(goodsId, time);
            redis.opsForValue().setIfAbsent(SeckillKeys.stock(goodsId), String.valueOf(goods.getStock()));
        }
        long now = System.currentTimeMillis();
        if (now < time[0]) {
            throw new BusinessException(ResultCode.SECKILL_NOT_START);
        }
        if (now > time[1]) {
            throw new BusinessException(ResultCode.SECKILL_END);
        }

        // 2. 本地售罄标记，命中直接快速失败
        if (Boolean.TRUE.equals(soldOutMap.get(goodsId))) {
            throw new BusinessException(ResultCode.SECKILL_SOLD_OUT);
        }

        // 3. 校验动态秒杀地址
        String cachedPath = redis.opsForValue().get(SeckillKeys.path(goodsId, userId));
        if (cachedPath == null || !cachedPath.equals(path)) {
            throw new BusinessException(ResultCode.SECKILL_PATH_INVALID);
        }

        // 4. Lua 原子扣减（判断库存 + 判断重复 + 扣减，一次往返完成）
        Long result = redis.execute(seckillScript,
                Arrays.asList(SeckillKeys.stock(goodsId), SeckillKeys.users(goodsId)),
                String.valueOf(userId));

        if (result == null) {
            log.error("Lua 脚本返回 null: userId={}, goodsId={}", userId, goodsId);
            throw new BusinessException(ResultCode.SECKILL_FAILED);
        }
        if (result == -1L) {
            soldOutMap.put(goodsId, Boolean.TRUE);
            throw new BusinessException(ResultCode.SECKILL_SOLD_OUT);
        }
        if (result == -2L) {
            throw new BusinessException(ResultCode.SECKILL_REPEAT);
        }

        log.info("Redis 扣减成功，剩余库存={}, userId={}, goodsId={}", result, userId, goodsId);

        // 5. 先写"排队中"，再异步投递消息
        redis.opsForValue().set(SeckillKeys.result(goodsId, userId),
                QUEUING, 30, TimeUnit.MINUTES);
        producer.send(new SeckillMessage(userId, goodsId));
        return 0;
    }

    @Override
    public String getResult(Long userId, Long goodsId) {
        String result = redis.opsForValue().get(SeckillKeys.result(goodsId, userId));
        return result == null ? "NOT_FOUND" : result;
    }

    @Override
    public void warmUpAll() {
        List<SeckillGoods> list = goodsMapper.selectList(null);
        for (SeckillGoods goods : list) {
            timeMap.put(goods.getId(), new long[]{
                    toMillis(goods.getStartTime(), 0L),
                    toMillis(goods.getEndTime(), Long.MAX_VALUE)
            });
            // setIfAbsent 很关键：服务重启时如果 Redis 里已有扣减过的库存，
            // 不能用数据库初始库存覆盖，否则会超卖。
            redis.opsForValue().setIfAbsent(
                    SeckillKeys.stock(goods.getId()), String.valueOf(goods.getStock()));
        }
        log.info("库存预热完成，商品数量={}", list.size());
    }

    private long toMillis(LocalDateTime time, long defaultValue) {
        if (time == null) {
            return defaultValue;
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
