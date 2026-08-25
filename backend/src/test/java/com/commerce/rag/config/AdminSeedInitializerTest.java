package com.commerce.rag.config;

import static org.mockito.Mockito.verify;

import com.commerce.rag.properties.AdminSeedProperties;
import com.commerce.rag.service.ISysUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

/**
 * AdminSeedInitializer 单元测试 —— 启动种子委托链路
 *
 * <p>验证 ApplicationRunner 将配置的超管凭证（含出厂默认密码标记 admin123）
 * 原样委托给 ISysUserService#ensureSeedSuperAdmin，幂等写入语义由服务层测试覆盖。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminSeedInitializer 超管种子启动初始化测试")
class AdminSeedInitializerTest {

    @Mock
    private ISysUserService sysUserService;

    @Mock
    private ApplicationArguments args;

    @Test
    @DisplayName("run → 按配置凭证 + 出厂默认密码标记委托服务层种子写入")
    void run_delegatesConfiguredCredentialsToService() {
        AdminSeedProperties props = new AdminSeedProperties("admin", "admin123", "系统管理员");
        AdminSeedInitializer initializer = new AdminSeedInitializer(props, sysUserService);

        initializer.run(args);

        verify(sysUserService).ensureSeedSuperAdmin("admin", "admin123", "系统管理员", "admin123");
    }

    @Test
    @DisplayName("run → env 覆盖后的真实值同样原样委托（admin123 仅作出厂默认标记）")
    void run_delegatesEnvOverriddenCredentialsToService() {
        AdminSeedProperties props = new AdminSeedProperties("ops-root", "real-secret", "运维管理员");
        AdminSeedInitializer initializer = new AdminSeedInitializer(props, sysUserService);

        initializer.run(args);

        verify(sysUserService).ensureSeedSuperAdmin("ops-root", "real-secret", "运维管理员", "admin123");
    }
}
