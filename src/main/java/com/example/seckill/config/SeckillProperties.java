package com.example.seckill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "seckill")
public class SeckillProperties {

    /**
     * 接口加盐值，用于生成动态秒杀地址
     */
    private String salt = "default-salt";

    /**
     * 秒杀地址有效期（秒）
     */
    private Integer pathTtlSeconds = 60;

    /**
     * 单用户每秒请求上限
     */
    private Integer rateLimitPerSecond = 5;
}
