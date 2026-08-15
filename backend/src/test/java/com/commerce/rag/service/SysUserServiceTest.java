package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.controller.dto.CreateUserRequest;
import com.commerce.rag.controller.dto.UpdateUserRequest;
import com.commerce.rag.controller.dto.UserDTO;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.enums.UserRole;
import com.commerce.rag.mapper.SysUserMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * SysUserService 单元测试 —— 用户 CRUD + 权限校验
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SysUserService 用户管理测试")
class SysUserServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private DeviceKickService deviceKickService;

    /** 真实转换器实现（MapStruct 生成），保证 toDTO 走真实字段映射而非 mock */
    @Spy
    private SysUserConverter sysUserConverter = new SysUserConverterImpl();

    @InjectMocks
    private SysUserService sysUserService;

    @BeforeEach
    void setUp() {
        // 公共 stub（lenient 因为非所有测试都用到）
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");
        // P2-2: create/findPage 按 DB 最新角色判定（resolveDbRole）——默认操作者为超管；
        // 教师角色用例在各自测试内覆盖此 stub（后 stub 生效）
        SysUser defaultOperator = new SysUser();
        defaultOperator.setId(100L);
        defaultOperator.setRole("SUPER_ADMIN");
        lenient().when(userMapper.selectById(100L)).thenReturn(defaultOperator);
    }

    // ==================== create() 测试 ====================

    @Test
    @DisplayName("create → 正常创建用户")
    void create_normalUser_success() {
        when(userMapper.selectCount(any())).thenReturn(0L);

        CreateUserRequest request = new CreateUserRequest("testuser", "password123", "测试用户", "STUDENT");

        UserDTO result = sysUserService.create(request, 100L);

        assertNotNull(result);
        assertEquals("testuser", result.username());
        assertEquals("STUDENT", result.role());
        assertEquals("ACTIVE", result.status());
        verify(userMapper).insert(any(SysUser.class));
    }

    @Test
    @DisplayName("create → 用户名重复抛出 409")
    void create_duplicateUsername_throws409() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        CreateUserRequest request = new CreateUserRequest("existinguser", "password123", "已存在", "STUDENT");

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> sysUserService.create(request, 100L));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("create → 超管已存在时创建超管抛出 409")
    void create_superAdminAlreadyExists_throws409() {
        // 第一次 selectCount（用户名查重）返回 0
        // 第二次 selectCount（超管查重）返回 1
        when(userMapper.selectCount(any())).thenReturn(0L, 1L);

        CreateUserRequest request = new CreateUserRequest("newadmin", "password123", "新超管", "SUPER_ADMIN");

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> sysUserService.create(request, 100L));
        assertEquals(409, ex.getStatusCode().value());
    }

    // ==================== findById() 测试 ====================

    @Test
    @DisplayName("findById → 用户存在返回 DTO")
    void findById_userExists_returnsDTO() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("testuser");
        user.setDisplayName("测试");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        when(userMapper.selectById(1L)).thenReturn(user);

        UserDTO result = sysUserService.findById(1L);

        assertNotNull(result);
        assertEquals(1L, result.id());
        assertEquals("testuser", result.username());
    }

    @Test
    @DisplayName("findById → 用户不存在抛出 404")
    void findById_userNotExists_throws404() {
        when(userMapper.selectById(999L)).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> sysUserService.findById(999L));
        assertEquals(404, ex.getStatusCode().value());
    }

    // ==================== updateStatus() 测试 ====================

    @Test
    @DisplayName("updateStatus → 禁用超管抛出 403")
    void updateStatus_disableSuperAdmin_throws403() {
        SysUser superAdmin = new SysUser();
        superAdmin.setId(1L);
        superAdmin.setRole(UserRole.SUPER_ADMIN.name());
        when(userMapper.selectById(1L)).thenReturn(superAdmin);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> sysUserService.updateStatus(1L, "DISABLED", 100L));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("updateStatus → 禁用普通用户触发 deviceKickService.disableUser")
    void updateStatus_disableNormalUser_triggersDisableUser() {
        SysUser student = new SysUser();
        student.setId(2L);
        student.setRole(UserRole.STUDENT.name());

        when(userMapper.selectById(2L)).thenReturn(student);

        // 操作者是超管
        SysUser admin = new SysUser();
        admin.setId(100L);
        admin.setRole(UserRole.SUPER_ADMIN.name());
        // 第二次 selectById（操作者查询）
        lenient().when(userMapper.selectById(100L)).thenReturn(admin);

        sysUserService.updateStatus(2L, "DISABLED", 100L);

        verify(deviceKickService).disableUser(2L, 100L);
        verify(userMapper).update(any(), any());
    }

    // ==================== delete() 测试 ====================

    @Test
    @DisplayName("delete → 超管不可删抛出 403")
    void delete_superAdmin_throws403() {
        SysUser superAdmin = new SysUser();
        superAdmin.setId(1L);
        superAdmin.setRole(UserRole.SUPER_ADMIN.name());
        when(userMapper.selectById(1L)).thenReturn(superAdmin);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> sysUserService.delete(1L, 100L));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("delete → 正常用户触发 disableUser + 软删除")
    void delete_normalUser_triggersDisableAndSoftDelete() {
        SysUser student = new SysUser();
        student.setId(2L);
        student.setRole(UserRole.STUDENT.name());

        when(userMapper.selectById(2L)).thenReturn(student);

        SysUser admin = new SysUser();
        admin.setId(100L);
        admin.setRole(UserRole.SUPER_ADMIN.name());
        lenient().when(userMapper.selectById(100L)).thenReturn(admin);

        sysUserService.delete(2L, 100L);

        verify(deviceKickService).disableUser(2L, 100L);
        verify(userMapper).update(any(), any());
    }

    // ==================== update() 测试 ====================

    @Test
    @DisplayName("update → 正常更新用户信息")
    void update_normalUpdate_success() {
        SysUser student = new SysUser();
        student.setId(2L);
        student.setRole(UserRole.STUDENT.name());

        when(userMapper.selectById(2L)).thenReturn(student);

        SysUser admin = new SysUser();
        admin.setId(100L);
        admin.setRole(UserRole.SUPER_ADMIN.name());
        lenient().when(userMapper.selectById(100L)).thenReturn(admin);

        UpdateUserRequest request = new UpdateUserRequest("新名字");

        UserDTO result = sysUserService.update(2L, request, 100L);

        assertNotNull(result);
        verify(userMapper).updateById(any(SysUser.class));
    }

    // ==================== P0-2 教师越权修复用例 ====================

    @Test
    @DisplayName("create → 教师创建 TEACHER 账号抛出 403，创建 STUDENT 成功")
    void create_teacherCreatesTeacherRole_throws403() {
        // P2-2: 角色判定按 DB 最新角色（stub selectById(100L) → TEACHER，覆盖 setUp 的超管 stub）
        SysUser teacher = new SysUser();
        teacher.setId(100L);
        teacher.setRole("TEACHER");
        when(userMapper.selectById(100L)).thenReturn(teacher);
        CreateUserRequest req = new CreateUserRequest("stu1", "pass123", "学生一", "TEACHER");

        assertThrows(ResponseStatusException.class, () -> sysUserService.create(req, 100L));

        CreateUserRequest stuReq = new CreateUserRequest("stu1", "pass123", "学生一", "STUDENT");
        assertDoesNotThrow(() -> sysUserService.create(stuReq, 100L));
        // 落库用户 created_by = 创建者
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertEquals(100L, captor.getValue().getCreatedBy());
    }

    @Test
    @DisplayName("checkTeacherPermission → 教师操作非自己创建的学生抛出 403")
    void teacherOperatesStudentNotCreatedBySelf_throws403() {
        SysUser target = new SysUser();
        target.setId(2L);
        target.setRole("STUDENT");
        target.setCreatedBy(999L); // 由他人创建
        SysUser operator = new SysUser();
        operator.setId(100L);
        operator.setRole("TEACHER");
        when(userMapper.selectById(100L)).thenReturn(operator);
        when(userMapper.selectById(2L)).thenReturn(target);

        assertThrows(ResponseStatusException.class, () -> sysUserService.updateStatus(2L, "DISABLED", 100L));
    }

    @Test
    @DisplayName("findPage → 教师仅能查到创建者为自己的用户")
    void findPage_teacherFiltersByCreatedBy() {
        // P2-2: 教师过滤按 DB 最新角色判定（stub selectById(100L) → TEACHER，覆盖 setUp 的超管 stub）
        SysUser teacher = new SysUser();
        teacher.setId(100L);
        teacher.setRole("TEACHER");
        when(userMapper.selectById(100L)).thenReturn(teacher);
        // stub 分页返回空页（防止 selectPage 默认 null 导致 convert NPE）
        when(userMapper.selectPage(any(), any())).thenReturn(new Page<SysUser>(1, 20));

        sysUserService.findPage(1, 20, null, null, 100L);
        // 校验查询条件带 created_by = 100
        ArgumentCaptor<LambdaQueryWrapper<SysUser>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectPage(any(), captor.capture());
        // wrapper 内部 SQL 片段含 created_by 条件
        assertTrue(captor.getValue().getCustomSqlSegment().contains("created_by"));
    }

    @Test
    @DisplayName("findById → 教师查看非自己创建的学生返回 null")
    void findById_teacherNotOwner_returnsNull() {
        SysUser target = new SysUser();
        target.setId(2L);
        target.setRole("STUDENT");
        target.setCreatedBy(999L);
        when(userMapper.selectById(2L)).thenReturn(target);

        assertNull(sysUserService.findById(2L, 100L, "TEACHER"));
    }

    @Test
    @DisplayName("checkTeacherPermission → operator 角色为 STUDENT 时抛出 403（fail-closed）")
    void operatorWithStudentRole_throws403() {
        SysUser target = new SysUser();
        target.setId(2L);
        target.setRole("STUDENT");
        SysUser operator = new SysUser();
        operator.setId(100L);
        operator.setRole("STUDENT");
        when(userMapper.selectById(100L)).thenReturn(operator);
        when(userMapper.selectById(2L)).thenReturn(target);

        // 非超管/教师角色一律拒绝，不得 fall-through 放行
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> sysUserService.updateStatus(2L, "DISABLED", 100L));
        assertEquals(403, ex.getStatusCode().value());
    }

    // ==================== resetPassword() / delete() 补充 ====================

    @Test
    @DisplayName("resetPassword → 用户不存在抛 404")
    void resetPassword_userNotFound_throws404() {
        when(userMapper.selectById(99L)).thenReturn(null);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> sysUserService.resetPassword(99L, "newpass", 100L));

        assertEquals(404, ex.getStatusCode().value());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("resetPassword → 超管重置任意用户密码并加密存储")
    void resetPassword_superAdmin_resetsPassword() {
        SysUser target = new SysUser();
        target.setId(2L);
        target.setRole("STUDENT");
        target.setCreatedBy(1L);
        when(userMapper.selectById(2L)).thenReturn(target);

        sysUserService.resetPassword(2L, "newpass", 100L);

        verify(passwordEncoder).encode("newpass");
        verify(userMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("delete → 用户不存在抛 404")
    void delete_userNotFound_throws404() {
        when(userMapper.selectById(99L)).thenReturn(null);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> sysUserService.delete(99L, 100L));

        assertEquals(404, ex.getStatusCode().value());
    }

    // ==================== findAuthViewByUsername ====================

    @Test
    @DisplayName("findAuthViewByUsername → 命中返回认证视图（含密码哈希，Entity 不出边界）")
    void findAuthViewByUsername_hit_returnsView() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hashed-pass");
        user.setRole("STUDENT");
        user.setDisplayName("测试用户");
        user.setStatus("ACTIVE");
        when(userMapper.selectOne(any())).thenReturn(user);

        AuthUserView view = sysUserService.findAuthViewByUsername("testuser");

        assertEquals(1L, view.id());
        assertEquals("hashed-pass", view.passwordHash());
        assertEquals("STUDENT", view.role());
        assertEquals("ACTIVE", view.status());
    }

    @Test
    @DisplayName("findAuthViewByUsername → 未命中返回 null")
    void findAuthViewByUsername_miss_returnsNull() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertNull(sysUserService.findAuthViewByUsername("unknown"));
    }
}
