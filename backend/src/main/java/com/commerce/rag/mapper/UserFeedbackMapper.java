package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.commerce.rag.entity.UserFeedback;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户反馈 Mapper —— MyBatis-Plus BaseMapper 接口
 *
 * <p>单表 CRUD 由 BaseMapper 提供，无需手写 SQL；
 * 按天分组统计反馈数（Dashboard 趋势图）为分组聚合 SQL，在 UserFeedbackMapper.xml 中映射实现。
 *
 * @author commerce-rag
 */
@Mapper
public interface UserFeedbackMapper extends BaseMapper<UserFeedback> {

    /**
     * 按天分组统计反馈数（Dashboard 趋势图，P2-2）
     *
     * <p>分组聚合 SQL（to_char/COUNT/GROUP BY）在 UserFeedbackMapper.xml 实现，
     * 不在 service 拼接 SQL 字符串（宪法：复杂 SQL 必须走 mapper XML 映射）。
     *
     * @param start 统计起始时间（含当天 0 点）
     * @return 每行 {d: 'YYYY-MM-DD', c: 计数} 的列表，日期升序（deleted=0 已过滤）
     */
    List<Map<String, Object>> selectDailyFeedbackCount(@Param("start") LocalDateTime start);
}
