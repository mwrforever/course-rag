package com.commerce.rag.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置 —— 注册分页拦截器
 *
 * <p>设计文档 §5.7 要求：
 * <ul>
 *   <li>{@link PaginationInnerInterceptor}（maxLimit=2000，防全表查询）</li>
 *   <li>数据库类型：{@link DbType#POSTGRE_SQL}</li>
 * </ul>
 *
 * <p>未配置此拦截器时，所有使用 {@code Page<>} 的分页查询将返回全量数据（无 LIMIT），
 * 且无 maxLimit 防护，可能导致 OOM。
 *
 * @author commerce-rag
 */
@Configuration
public class MyBatisPlusConfig {

    /** 分页最大限制，防止全表查询 */
    private static final long MAX_LIMIT = 2000L;

    /**
     * 注册 MyBatis-Plus 拦截器链
     *
     * @return 包含分页拦截器的 MybatisPlusInterceptor
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页拦截器：指定 PostgreSQL 方言，设置 maxLimit 防全表查询
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        paginationInterceptor.setMaxLimit(MAX_LIMIT);
        interceptor.addInnerInterceptor(paginationInterceptor);
        return interceptor;
    }
}
