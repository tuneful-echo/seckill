package com.example.seckill.controller;

import com.example.seckill.common.BusinessException;
import com.example.seckill.common.Result;
import com.example.seckill.common.ResultCode;
import com.example.seckill.config.SeckillProperties;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.service.SeckillService;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    private final SeckillService seckillService;
    private final SeckillProperties properties;

    /**
     * 单用户限流器缓存。用 Guava Cache 而不是普通 Map，
     * 是因为 Map 只增不减会内存泄漏，这里设置最大容量 + 访问过期。
     */
    private final Cache<Long, RateLimiter> limiterCache = CacheBuilder.newBuilder()
            .maximumSize(20000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    public SeckillController(SeckillService seckillService, SeckillProperties properties) {
        this.seckillService = seckillService;
        this.properties = properties;
    }

    /**
     * 商品列表（前端据此渲染倒计时）
     */
    @GetMapping("/list")
    public Result<List<SeckillGoods>> list() {
        return Result.ok(seckillService.listGoods());
    }

    /**
     * 商品详情
     */
    @GetMapping("/detail/{goodsId}")
    public Result<SeckillGoods> detail(@PathVariable Long goodsId) {
        return Result.ok(seckillService.getGoods(goodsId));
    }

    /**
     * 获取动态秒杀地址。
     * 前端先调这个拿到 path，再拿 path 去调秒杀接口，防止脚本直接写死接口 URL 刷单。
     */
    @GetMapping("/path/{goodsId}")
    public Result<String> path(@RequestHeader(value = "userId", required = false) Long userId,
                               @PathVariable Long goodsId) {
        requireLogin(userId);
        return Result.ok(seckillService.createPath(userId, goodsId));
    }

    /**
     * 执行秒杀
     */
    @PostMapping("/do/{goodsId}")
    public Result<Integer> doSeckill(@RequestHeader(value = "userId", required = false) Long userId,
                                     @PathVariable Long goodsId,
                                     @RequestParam("path") String path) {
        requireLogin(userId);

        // 单用户令牌桶限流：默认每秒 5 次，超出直接拒绝
        RateLimiter limiter = limiterCache.getIfPresent(userId);
        if (limiter == null) {
            limiter = RateLimiter.create(properties.getRateLimitPerSecond());
            limiterCache.put(userId, limiter);
        }
        if (!limiter.tryAcquire()) {
            return Result.fail(ResultCode.SECKILL_REQUEST_TOO_FREQUENT);
        }

        int result = seckillService.seckill(userId, goodsId, path);
        if (result == 0) {
            return Result.ok(0);
        }
        return Result.fail(ResultCode.SECKILL_FAILED);
    }

    /**
     * 查询秒杀结果（异步下单，前端轮询这个接口）
     * 返回 QUEUING / SOLD_OUT / 订单ID / NOT_FOUND
     */
    @GetMapping("/result/{goodsId}")
    public Result<String> result(@RequestHeader(value = "userId", required = false) Long userId,
                                 @PathVariable Long goodsId) {
        requireLogin(userId);
        return Result.ok(seckillService.getResult(userId, goodsId));
    }

    private void requireLogin(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.FAILED, "缺少 userId，请先登录");
        }
    }
}
