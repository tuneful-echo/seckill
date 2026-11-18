-- ============================================================
-- 高并发秒杀系统建表脚本
-- MySQL 8.0
-- 执行方式：mysql -uroot -p < seckill.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS `seckill` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `seckill`;

-- ------------------------------------------------------------
-- 秒杀商品表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `seckill_goods`;
CREATE TABLE `seckill_goods` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title`          VARCHAR(120)    NOT NULL COMMENT '商品标题',
  `sub_title`      VARCHAR(200)    DEFAULT NULL COMMENT '副标题/卖点',
  `price`          DECIMAL(10,2)   NOT NULL COMMENT '原价',
  `seckill_price`  DECIMAL(10,2)   NOT NULL COMMENT '秒杀价',
  `stock`          INT             NOT NULL DEFAULT 0 COMMENT '数据库剩余库存',
  `total_stock`    INT             NOT NULL DEFAULT 0 COMMENT '总库存（用于校验超卖）',
  `start_time`     DATETIME        NOT NULL COMMENT '秒杀开始时间',
  `end_time`       DATETIME        NOT NULL COMMENT '秒杀结束时间',
  `version`        INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（DB 兜底扣减用）',
  `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_time` (`start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品表';

-- ------------------------------------------------------------
-- 秒杀订单表
-- 关键：uk_user_goods 唯一索引是"重复下单/消息重复消费"的最后一道防线
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `seckill_order`;
CREATE TABLE `seckill_order` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no`     VARCHAR(40)     NOT NULL COMMENT '订单号',
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '下单用户',
  `goods_id`     BIGINT UNSIGNED NOT NULL COMMENT '秒杀商品',
  `goods_price`  DECIMAL(10,2)   NOT NULL COMMENT '成交价',
  `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消',
  `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_user_goods` (`user_id`, `goods_id`),
  KEY `idx_goods` (`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单表';

-- ------------------------------------------------------------
-- 初始化演示数据：4 个商品，库存分别 100 / 50 / 200 / 30
-- ------------------------------------------------------------
INSERT INTO `seckill_goods`
(`title`, `sub_title`, `price`, `seckill_price`, `stock`, `total_stock`, `start_time`, `end_time`)
VALUES
('AirPods Pro 2',      '主动降噪 · 秒杀限量',   1899.00,  999.00, 100, 100, NOW(), DATE_ADD(NOW(), INTERVAL 2 HOUR)),
('机械键盘 87 键',     '茶轴 · 全键无冲',        499.00,  199.00,  50,  50, NOW(), DATE_ADD(NOW(), INTERVAL 2 HOUR)),
('显示器 27寸 2K',     '165Hz · IPS 面板',      1599.00,  899.00, 200, 200, NOW(), DATE_ADD(NOW(), INTERVAL 2 HOUR)),
('移动电源 20000mAh',  '65W 快充 · 双向输出',   249.00,   89.00,  30,  30, NOW(), DATE_ADD(NOW(), INTERVAL 2 HOUR));

-- ------------------------------------------------------------
-- 压测后用于验证是否超卖的 SQL（结果必须为 0）
-- ------------------------------------------------------------
-- SELECT g.id, g.total_stock, COUNT(o.id) AS sold
-- FROM seckill_goods g LEFT JOIN seckill_order o ON o.goods_id = g.id
-- GROUP BY g.id, g.total_stock
-- HAVING sold > g.total_stock;
