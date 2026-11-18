package com.example.seckill.task;

import com.example.seckill.service.SeckillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把库存预热到 Redis。
 * 秒杀开始后所有请求都在 Redis 上判断，数据库不参与热点路径。
 */
@Slf4j
@Component
public class StockWarmUpRunner implements ApplicationRunner {

    private final SeckillService seckillService;

    public StockWarmUpRunner(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seckillService.warmUpAll();
        } catch (Exception e) {
            // 预热失败不阻断启动，但必须打日志，否则秒杀会全部失败
            log.error("库存预热失败，请检查 MySQL 与 Redis 连接", e);
        }
    }
}
