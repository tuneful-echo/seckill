package com.example.seckill.common;

/**
 * Redis Key 统一管理，避免 Service 和 Consumer 各写一套导致 Key 不一致
 */
public final class SeckillKeys {

    private SeckillKeys() {
    }

    /** 库存：string 类型，值为剩余数量 */
    private static final String STOCK_PREFIX = "seckill:stock:";

    /** 已抢购用户集合：set 类型，成员为 userId */
    private static final String USERS_PREFIX = "seckill:users:";

    /** 动态秒杀地址：string 类型 */
    private static final String PATH_PREFIX = "seckill:path:";

    /** 秒杀结果：string 类型，QUEUING / SOLD_OUT / 订单ID */
    private static final String RESULT_PREFIX = "seckill:result:";

    public static String stock(Long goodsId) {
        return STOCK_PREFIX + goodsId;
    }

    public static String users(Long goodsId) {
        return USERS_PREFIX + goodsId;
    }

    public static String path(Long goodsId, Long userId) {
        return PATH_PREFIX + goodsId + ":" + userId;
    }

    public static String result(Long goodsId, Long userId) {
        return RESULT_PREFIX + goodsId + ":" + userId;
    }
}
