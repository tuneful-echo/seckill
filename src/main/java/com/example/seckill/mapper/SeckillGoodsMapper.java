package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.SeckillGoods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillGoodsMapper extends BaseMapper<SeckillGoods> {

    /**
     * 数据库兜底扣减库存。
     * WHERE stock > 0 保证并发下不会把库存扣成负数，是防超卖的最后一道防线。
     *
     * @return 1 表示扣减成功，0 表示库存不足
     */
    @Update("UPDATE seckill_goods SET stock = stock - 1 WHERE id = #{goodsId} AND stock > 0")
    int reduceStock(@Param("goodsId") Long goodsId);

    /**
     * 库存补偿回补：订单写入失败（如唯一索引冲突）时调用，
     * 把刚才扣掉的库存加回去，避免少卖。
     */
    @Update("UPDATE seckill_goods SET stock = stock + 1 WHERE id = #{goodsId}")
    int incrementStock(@Param("goodsId") Long goodsId);
}
