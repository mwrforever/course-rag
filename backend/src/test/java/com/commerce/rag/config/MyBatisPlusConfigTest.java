package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MyBatisPlusConfig 单元测试 —— 验证分页拦截器注册与 maxLimit 防护
 *
 * @author commerce-rag
 */
@DisplayName("MyBatisPlusConfig 分页拦截器测试")
class MyBatisPlusConfigTest {

    @Test
    @DisplayName("mybatisPlusInterceptor 注册 PostgreSQL 分页拦截器且 maxLimit=2000")
    void mybatisPlusInterceptor_registersPaginationWithMaxLimit() {
        MybatisPlusInterceptor interceptor = new MyBatisPlusConfig().mybatisPlusInterceptor();

        // 拦截器链中应包含且仅包含一个分页拦截器
        assertEquals(1, interceptor.getInterceptors().size());
        PaginationInnerInterceptor pagination =
                (PaginationInnerInterceptor) interceptor.getInterceptors().get(0);
        // 方言为 PostgreSQL，且 maxLimit 为 2000（防全表查询）
        assertEquals(DbType.POSTGRE_SQL, pagination.getDbType());
        assertEquals(2000L, pagination.getMaxLimit());
    }
}
