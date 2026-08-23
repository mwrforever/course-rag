package com.commerce.rag.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置 —— 注册分页拦截器与雪花 ID 生成器
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
 * <p>显式注册 {@link IdentifierGenerator}：MyBatis-Plus 3.5.12 的自动配置
 * （MybatisPlusAutoConfiguration）不注册该 Bean（内部以 GlobalConfig 持有默认实例，
 * 不暴露为容器 Bean）——自定义 SQL upsert 等需要显式预生成雪花 ID 的组件
 * （如 UserFeedbackServiceImpl P1-5）构造注入时将因 NoSuchBeanDefinition 失败，
 * 故在此以 @Bean 暴露与 ASSIGN_ID 同源的默认生成器。
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

    /**
     * 暴露雪花 ID 生成器为容器 Bean
     *
     * <p>MP 自动配置不注册该类型 Bean（见类注释），需要显式预生成 ID 的组件
     * 经构造注入使用；与实体 @TableId(ASSIGN_ID) 的填充来源保持同源一致。
     *
     * @return 默认雪花 ID 生成器（DefaultIdentifierGenerator 单例，线程安全）
     */
    @Bean
    public IdentifierGenerator identifierGenerator() {
        return new DefaultIdentifierGenerator();
    }
}
