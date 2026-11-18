package com.example.seckill.common;

import lombok.Getter;

@Getter
public enum ResultCode {

    SUCCESS(0, "成功"),
    FAILED(500, "失败"),
    PARAM_INVALID(400, "参数不合法"),

    // 秒杀业务码
    SECKILL_NOT_START(1001, "秒杀尚未开始"),
    SECKILL_END(1002, "秒杀已结束"),
    SECKILL_SOLD_OUT(1003, "商品已售罄"),
    SECKILL_REPEAT(1004, "每人限购一件"),
    SECKILL_PATH_INVALID(1005, "秒杀地址无效"),
    SECKILL_REQUEST_TOO_FREQUENT(1006, "请求过于频繁，请稍后再试"),
    SECKILL_QUEUING(1007, "排队中，请稍后查询结果"),
    SECKILL_FAILED(1008, "秒杀失败");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
