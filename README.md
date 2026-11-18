# 高并发秒杀系统（Seckill）

> 个人项目 · Java 后端 · SpringBoot 2.7 + MySQL + Redis + RabbitMQ

一个完整可运行的秒杀系统后端，聚焦**高并发场景下的三个核心问题**：防超卖、削峰填谷、消息幂等。
不依赖前端页面，全部接口可用 curl 或压测脚本直接验证。

---

## 一、请求链路

```
客户端
  │
  │ 1. GET /seckill/path/{goodsId}   获取动态加盐地址
  ▼
Controller
  │ 2. 单用户令牌桶限流（Guava RateLimiter）
  │ 3. 本地售罄标记快速失败（ConcurrentHashMap）
  │ 4. 校验动态 path
  ▼
Redis（Lua 脚本原子执行）
  │ 5. 库存判断 + 一人一单判断 + 库存扣减，一次往返完成
  ▼
RabbitMQ
  │ 6. 投递消息后接口立即返回（RT 极低）
  ▼
Consumer（异步）
  │ 7. 幂等校验（结果键 + 唯一索引）
  │ 8. 数据库兜底扣减（WHERE stock > 0）
  │ 9. 插入订单，冲突则回补库存
  ▼
MySQL
```

---

## 二、技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| SpringBoot | 2.7.18 | 基础框架 |
| MyBatis-Plus | 3.5.7 | ORM |
| MySQL | 8.0 | 订单与商品持久化 |
| Redis | 6.x+ | 库存缓存、Lua 原子扣减、结果查询 |
| RabbitMQ | 3.x | 异步下单、削峰 |
| Guava | 32.1.3 | 单用户令牌桶限流 |
| Lombok | - | 简化实体类 |

---

## 三、目录结构

```
seckill/
├── pom.xml
├── sql/
│   └── seckill.sql                 建库建表 + 初始化数据
├── scripts/
│   └── pressure_test.py            Python 并发压测脚本
└── src/main/
    ├── java/com/example/seckill/
    │   ├── SeckillApplication.java
    │   ├── common/                 Result、业务异常、全局异常、Redis Key 常量
    │   ├── config/                 RabbitMQ 配置、业务配置
    │   ├── controller/             SeckillController
    │   ├── entity/                 SeckillGoods、SeckillOrder
    │   ├── mapper/                 MyBatis-Plus Mapper
    │   ├── mq/                     消息体、生产者、消费者
    │   ├── service/                业务接口与实现（核心）
    │   └── task/                   启动库存预热
    └── resources/
        ├── application.yml
        └── lua/seckill.lua         原子扣减脚本
```

---

## 四、快速启动

### 1. 前置环境

- JDK 8 / 11 / 17
- Maven 3.6+
- MySQL 8.0
- Redis 6.x
- RabbitMQ 3.x

### 2. 初始化数据库

```bash
mysql -uroot -p < sql/seckill.sql
```

### 3. 修改配置

打开 `src/main/resources/application.yml`，把 `spring.datasource.password` 改成你自己的 MySQL 密码；
如果 Redis / RabbitMQ 不是本机默认端口，同步修改。

### 4. 启动

```bash
mvn spring-boot:run
```

启动日志里出现 `库存预热完成，商品数量=4` 说明 MySQL 和 Redis 都通了。

### 5. 冒烟测试

```bash
# 商品列表
curl http://localhost:8080/seckill/list

# 获取动态地址（userId 用 header 模拟登录态）
curl -H "userId: 1" http://localhost:8080/seckill/path/1

# 执行秒杀（path 换成上一步返回值）
curl -X POST -H "userId: 1" "http://localhost:8080/seckill/do/1?path=你的path值"

# 查询结果：QUEUING / 订单ID / SOLD_OUT
curl -H "userId: 1" http://localhost:8080/seckill/result/1
```

---

## 五、压测与数据

```bash
pip install requests
python scripts/pressure_test.py --users 1000 --goods 1 --workers 50
```

脚本会输出：总请求数、总耗时、**QPS**、排队成功/售罄/重复/限流数量，等待队列消费后再统计**实际成交单数**。

建议做三组对比，这组数据就是简历和面试里能拿出来讲的东西：

| 场景 | 观察指标 |
|---|---|
| 库存 100，1000 并发用户 | QPS、成交数=100、超卖=0 |
| 同一用户连点 10 次 | 重复下单被拦截，只剩 1 单 |
| 关掉 RabbitMQ 对比同步下单 | 接口 RT 明显上升 |

超卖校验 SQL（结果必须为空）：

```sql
SELECT g.id, g.total_stock, COUNT(o.id) AS sold
FROM seckill_goods g
LEFT JOIN seckill_order o ON o.goods_id = g.id
GROUP BY g.id, g.total_stock
HAVING sold > g.total_stock;
```

---

## 六、五个核心设计点（面试就讲这些）

### 1. 防超卖：Redis + Lua 原子扣减

`GET 库存 → 判断 → DECR` 拆成多条命令时，两个请求可能同时读到 `stock=1` 都判断通过，最终扣成 -1。
把这三步放进同一个 Lua 脚本，Redis 单线程执行脚本期间不会被其他命令插入，天然原子。

### 2. 削峰：RabbitMQ 异步下单

Redis 扣减成功后立刻发消息返回，数据库落库交给消费者慢慢做。
好处：接口 RT 从"等 DB 写入"的几十毫秒降到几毫秒；流量洪峰被队列缓冲，数据库不会被瞬时打穿。

### 3. 幂等：三道防线

1. Redis 结果键已存在 → 判定重复消息，直接跳过
2. 消费前查库，已有订单 → 回写结果返回
3. 订单表唯一索引 `uk_user_goods(user_id, goods_id)` → 数据库层最终拦截

第 3 道之所以必要：前两道在极端并发下都可能同时通过，只有唯一索引是原子的。

### 4. 库存一致性：唯一索引冲突后的补偿

扣库存成功但插订单被唯一索引拦截时，必须 `stock + 1` 回补，否则会出现"少卖"。
这是异步方案里最容易被忽略的坑，能在面试里主动说出来，说明真的写过。

### 5. 快速失败与限流

- **本地售罄标记**：库存归零后在内存打标，后续请求连 Redis 都不访问，把无效流量挡在最外层
- **动态加盐地址**：`MD5(salt + goodsId + userId)`，脚本猜不到接口路径，防刷单
- **单用户令牌桶**：Guava RateLimiter，默认每秒 5 次，超频直接拒绝

---

## 七、面试可能被追问的问题

| 追问 | 怎么答 |
|---|---|
| Redis 扣减成功但消息发送失败怎么办？ | 当前实现会抛异常让请求失败；生产应加本地消息表或事务消息做最终一致 |
| MQ 消费失败怎么处理？ | 默认自动 ack + 重试；生产建议配死信队列，重试超限进死信人工兜底 |
| Redis 挂了怎么办？ | 库存是热点数据，Redis 不可用会直接拒绝请求（限流保护），不能降级查库，否则数据库会被打穿 |
| 库存预热后 Redis 重启丢数据怎么办？ | 数据会回退到数据库库存，需要靠数据库 `WHERE stock > 0` 兜底，并接受少量少卖 |
| 为什么不用数据库乐观锁？ | 可以用，但高并发下大量事务冲突回滚、行锁竞争严重；Redis 预减把请求挡在数据库之前 |
| 一人一单为什么用 Set 不用 String？ | Set 天然去重且支持 `SISMEMBER` O(1) 判断；用 String 需要额外判断存在性 |

---

## 八、简历描述模板（可直接用）

> **高并发秒杀系统（个人项目）**
> 技术栈：SpringBoot · MySQL · Redis · RabbitMQ · MyBatis-Plus
>
> 独立实现秒杀核心链路：基于 Redis + Lua 脚本完成"库存校验-去重-扣减"原子操作，解决并发超卖；
> 引入 RabbitMQ 异步下单，将接口 RT 降至毫秒级并缓冲流量洪峰；
> 通过"结果键判重 + 唯一索引"双保险保证消息幂等，唯一索引冲突时自动回补库存避免少卖；
> 结合本地售罄标记、动态加盐地址、单用户令牌桶实现三级流量拦截；
> 压测 1000 并发用户抢 100 件库存，QPS 达到 XX，成交 100 单，超卖 0 单。

（QPS 数字用你自己压测的真实值填，不要编。）

---

## 九、可继续扩展的方向

- 订单超时未支付自动取消（延迟队列 / RabbitMQ TTL + 死信）
- 秒杀地址一次性校验（用完即删）
- 多级缓存 + Nginx 层限流
- 分布式限流（Redis + Lua 令牌桶）
- 压测报告整理成文档，作为项目成果附件
