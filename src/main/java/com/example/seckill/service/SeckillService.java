package com.example.seckill.service;

import com.example.seckill.entity.SeckillGoods;

import java.util.List;

public interface SeckillService {

    /**
     * 秒杀商品列表
     */
    List<SeckillGoods> listGoods();

    /**
     * 商品详情
     */
    SeckillGoods getGoods(Long goodsId);

    /**
     * 生成动态秒杀地址（加盐，防脚本提前知道接口路径）
     */
    String createPath(Long userId, Long goodsId);

    /**
     * 执行秒杀
     *
     * @return 0 表示成功进入排队；其余为业务错误码
     */
    int seckill(Long userId, Long goodsId, String path);

    /**
     * 查询秒杀结果：QUEUING / SOLD_OUT / 订单ID
     */
    String getResult(Long userId, Long goodsId);

    /**
     * 启动时把商品库存预热到 Redis
     */
    void warmUpAll();
}
