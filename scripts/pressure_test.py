#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
秒杀系统压测脚本

用法：
    pip install requests
    python pressure_test.py --users 1000 --goods 1 --workers 50

参数：
    --users    模拟用户数量（每个用户独立 userId，各自取自己的动态秒杀地址）
    --goods    秒杀商品 ID
    --workers  并发线程数
    --base     服务地址

输出：
    成功 / 售罄 / 重复 / 限流 / 其他失败 的数量、总耗时、QPS，
    以及异步落库完成后的实际成交单数。
"""

import argparse
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    import requests
except ImportError:
    print("缺少依赖，请先执行: pip install requests")
    sys.exit(1)


class Counter:
    def __init__(self):
        self._lock = threading.Lock()
        self.data = {
            "ok": 0,          # 排队成功
            "sold_out": 0,    # 售罄
            "repeat": 0,      # 重复下单
            "rate_limited": 0, # 被限流
            "other_fail": 0,  # 其他失败
            "network_err": 0, # 请求异常
        }

    def inc(self, key):
        with self._lock:
            self.data[key] += 1


def get_path(base, user_id, goods_id):
    """获取动态秒杀地址"""
    r = requests.get(
        f"{base}/seckill/path/{goods_id}",
        headers={"userId": str(user_id)},
        timeout=5,
    )
    body = r.json()
    if body.get("code") != 0:
        raise RuntimeError(f"获取 path 失败: {body}")
    return body["data"]


def do_seckill(base, user_id, goods_id, path, counter):
    """执行秒杀"""
    try:
        r = requests.post(
            f"{base}/seckill/do/{goods_id}",
            params={"path": path},
            headers={"userId": str(user_id)},
            timeout=5,
        )
        body = r.json()
        code = body.get("code")
        if code == 0:
            counter.inc("ok")
        elif code == 1003:
            counter.inc("sold_out")
        elif code == 1004:
            counter.inc("repeat")
        elif code == 1006:
            counter.inc("rate_limited")
        else:
            counter.inc("other_fail")
            print(f"[异常返回] userId={user_id} -> {body}")
        return code
    except Exception as e:
        counter.inc("network_err")
        print(f"[请求异常] userId={user_id} -> {e}")
        return None


def query_result(base, user_id, goods_id):
    """查询秒杀结果，返回订单ID或状态"""
    try:
        r = requests.get(
            f"{base}/seckill/result/{goods_id}",
            headers={"userId": str(user_id)},
            timeout=5,
        )
        return r.json().get("data")
    except Exception:
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--users", type=int, default=1000)
    parser.add_argument("--goods", type=int, default=1)
    parser.add_argument("--workers", type=int, default=50)
    parser.add_argument("--base", type=str, default="http://localhost:8080")
    parser.add_argument("--wait", type=int, default=5, help="等待异步落库的秒数")
    args = parser.parse_args()

    base = args.base.rstrip("/")
    goods_id = args.goods
    total_users = args.users

    print(f"目标: {base} / 商品ID: {goods_id} / 用户数: {total_users} / 并发: {args.workers}")
    print("-" * 60)

    # 阶段一：预热 path
    print("阶段 1/3  获取动态秒杀地址 ...")
    paths = {}
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=min(args.workers, 20)) as ex:
        futures = {
            ex.submit(get_path, base, u, goods_id): u
            for u in range(1, total_users + 1)
        }
        for f in as_completed(futures):
            u = futures[f]
            try:
                paths[u] = f.result()
            except Exception as e:
                print(f"[path 失败] userId={u} -> {e}")
    print(f"阶段 1 完成，成功获取 {len(paths)} 个 path，耗时 {time.time() - t0:.2f}s")

    if not paths:
        print("没有可用 path，检查服务是否启动、userId 是否正常")
        sys.exit(1)

    # 阶段二：并发秒杀
    print("-" * 60)
    print("阶段 2/3  并发执行秒杀 ...")
    counter = Counter()
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futures = [
            ex.submit(do_seckill, base, u, goods_id, p, counter)
            for u, p in paths.items()
        ]
        for f in as_completed(futures):
            f.result()
    elapsed = time.time() - t0

    qps = len(paths) / elapsed if elapsed > 0 else 0

    print("-" * 60)
    print(f"总请求     : {len(paths)}")
    print(f"总耗时     : {elapsed:.2f}s")
    print(f"QPS        : {qps:.1f}")
    print(f"排队成功   : {counter.data['ok']}")
    print(f"售罄返回   : {counter.data['sold_out']}")
    print(f"重复下单   : {counter.data['repeat']}")
    print(f"被限流     : {counter.data['rate_limited']}")
    print(f"其他失败   : {counter.data['other_fail']}")
    print(f"网络异常   : {counter.data['network_err']}")

    # 阶段三：等待异步落库并统计真实成交
    print("-" * 60)
    print(f"阶段 3/3  等待 {args.wait}s 让队列消费完，统计真实成交 ...")
    time.sleep(args.wait)

    order_ids = []
    for u in paths.keys():
        res = query_result(base, u, goods_id)
        if res and res.isdigit():
            order_ids.append(res)

    print(f"实际生成订单数（异步落库）: {len(order_ids)}")
    print("-" * 60)
    print("超卖校验请执行以下 SQL，结果必须为空：")
    print("""
    SELECT g.id, g.total_stock, COUNT(o.id) AS sold
    FROM seckill_goods g
    LEFT JOIN seckill_order o ON o.goods_id = g.id
    GROUP BY g.id, g.total_stock
    HAVING sold > g.total_stock;
    """)
    print("注意：Redis 剩余库存可执行  get seckill:stock:%d  核对是否 >= 0" % goods_id)


if __name__ == "__main__":
    main()
