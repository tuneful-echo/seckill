package com.example.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("seckill_goods")
public class SeckillGoods implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String subTitle;

    private BigDecimal price;

    private BigDecimal seckillPrice;

    private Integer stock;

    private Integer totalStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
