-- ============================================================
-- 秒杀核心 Lua 脚本：库存判断 + 一人一单 + 扣减，三步原子执行
--
-- KEYS[1] = seckill:stock:{goodsId}      库存（string 类型整数）
-- KEYS[2] = seckill:users:{goodsId}      已抢购用户集合（set）
-- ARGV[1] = userId
--
-- 返回值：
--   >= 0 : 扣减成功，返回扣减后的剩余库存
--   -1   : 库存不足（售罄）
--   -2   : 该用户已抢购过（重复下单）
--
-- 为什么必须用 Lua：
--   1. Redis 单线程执行 Lua，脚本内所有命令之间不会被其他客户端插队，
--      "读库存 -> 判断 -> 扣减" 天然原子，不会出现两个请求同时读到 stock=1 都扣成功。
--   2. 一次网络往返完成三次操作，比多次 GET/DECR 更快，压测时 RT 明显更低。
-- ============================================================

local stock = tonumber(redis.call('get', KEYS[1]))

-- 库存不存在（未预热）或已售罄
if stock == nil or stock <= 0 then
    return -1
end

-- 一人一单校验
if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
    return -2
end

-- 原子扣减 + 记录用户
redis.call('decr', KEYS[1])
redis.call('sadd', KEYS[2], ARGV[1])

return stock - 1
