package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.convert.SysUserConverter;
import com.commerce.rag.dto.CreateUserRequest;
import com.commerce.rag.dto.UpdateUserRequest;
import com.commerce.rag.dto.UserDTO;
import com.commerce.rag.entity.CourseTeacher;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.enums.UserRole;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.mapper.CourseTeacherMapper;
import com.commerce.rag.mapper.SysUserMapper;
import com.commerce.rag.record.AuthUserView;
import com.commerce.rag.service.ISysUserService;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 系统用户服务 —— 用户管理 CRUD
 *
 * <p>权限规则：
 * <ul>
 *   <li>超管不可删/不可禁用</li>
 *   <li>教师只能操作自己创建的学生</li>
 *   <li>软删除 + 级联（sys_login_record → REVOKED, jti → blacklist）</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {

    private static final Logger log = LoggerFactory.getLogger(ISysUserService.class);

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final DeviceKickService deviceKickService;
    private final CourseTeacherMapper courseTeacherMapper;
    private final SysUserConverter sysUserConverter;

    /** Dashboard 统计缓存（TTL 60 秒；用户增删影响 feedbackStats.studentCount，DB 写入后失效——BUG-2 修复） */
    @Qualifier("dashboardStatsCache")
    private final Cache<String, Object> dashboardStatsCache;

    /**
     * 创建用户
     *
     * @param request   创建请求（role 仅允许 SUPER_ADMIN / TEACHER / STUDENT）
     * @param createdBy 创建者用户 ID（用于归属记录与角色判定）
     * @return 用户 DTO
     */
    public UserDTO create(CreateUserRequest request, Long createdBy) {
        // P2-2: 按 DB 最新角色判定（不用 token 角色）——用户被降级后旧 AT 15min 窗口内
        // 不得继续创建学生账号，与 checkTeacherPermission 的 fail-closed 判定对齐
        String operatorRole = resolveDbRole(createdBy);
        // 教师只能创建学生账号（P0-2e：防止教师创建 TEACHER 扩权）
        if (UserRole.TEACHER.name().equals(operatorRole)
                && !UserRole.STUDENT.name().equals(request.role())) {
            throw new BizException(ErrorCode.FORBIDDEN, "教师只能创建学生账号");
        }

        // 用户名唯一性校验
        LambdaQueryWrapper<SysUser> wrapper =
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, request.username());
        Long existing = userMapper.selectCount(wrapper);
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }

        // 超管唯一性校验（全局仅 1 个超管）
        if (UserRole.SUPER_ADMIN.name().equals(request.role())) {
            LambdaQueryWrapper<SysUser> adminWrapper =
                    Wrappers.<SysUser>lambdaQuery().eq(SysUser::getRole, UserRole.SUPER_ADMIN.name());
            Long adminCount = userMapper.selectCount(adminWrapper);
            if (adminCount != null && adminCount > 0) {
                throw new BizException(ErrorCode.CONFLICT, "超级管理员已存在，全局仅允许 1 个");
            }
        }

        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName());
        user.setRole(request.role());
        user.setStatus("ACTIVE");
        user.setCreatedBy(createdBy);
        try {
            userMapper.insert(user);
        } catch (DataIntegrityViolationException e) {
            // B2-8: check-then-insert 竞态兜底——并发创建同名用户时查重双双通过、后者撞
            // uniq_sys_user_username，转 409 而非全局 503（语义与查重命中的友好提示一致）
            log.warn("并发创建用户名冲突: username={}, operator={}", request.username(), createdBy);
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在（并发操作冲突），请刷新后重试", e);
        }

        // 统计失效：学生数可能已变更（先写 DB 后失效——BUG-2 修复）
        dashboardStatsCache.invalidateAll();

        log.info(
                "创建用户: userId={}, username={}, role={}, createdBy={}",
                user.getId(),
                user.getUsername(),
                user.getRole(),
                createdBy);
        return sysUserConverter.toDTO(user);
    }

    /**
     * 根据 ID 查询用户
     */
    public UserDTO findById(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return sysUserConverter.toDTO(user);
    }

    /**
     * 根据 ID 查询用户（管理端，带教师归属过滤）
     *
     * @param id            目标用户 ID
     * @param currentUserId 当前操作者 ID
     * @param operatorRole  操作者角色
     * @return 用户 DTO；用户不存在或教师无归属权时返回 null（由 controller 统一映射 404）
     */
    public UserDTO findById(Long id, Long currentUserId, String operatorRole) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            return null;
        }
        // 教师只能查看自己创建的学生（P0-2f）
        if (UserRole.TEACHER.name().equals(operatorRole)
                && (user.getCreatedBy() == null || !user.getCreatedBy().equals(currentUserId))) {
            return null;
        }
        return sysUserConverter.toDTO(user);
    }

    /**
     * 根据用户名查询认证视图（含密码哈希，用于登录验证；Entity 不出 service 边界）
     *
     * @param username 用户名（精确匹配）
     * @return 认证视图，用户不存在返回 null
     */
    public AuthUserView findAuthViewByUsername(String username) {
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username);
        SysUser user = userMapper.selectOne(wrapper);
        return user == null
                ? null
                : new AuthUserView(
                        user.getId(),
                        user.getUsername(),
                        user.getPasswordHash(),
                        user.getRole(),
                        user.getDisplayName(),
                        user.getStatus());
    }

    /**
     * 分页查询用户
     *
     * @param page          页码（1-based）
     * @param size          每页条数
     * @param role          角色筛选（可空）
     * @param status        状态筛选（可空）
     * @param currentUserId 当前操作者 ID（教师过滤用）
     * @return 分页结果
     */
    public IPage<UserDTO> findPage(int page, int size, String role, String status, Long currentUserId) {
        Page<SysUser> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery().orderByDesc(SysUser::getCreatedAt);
        if (role != null && !role.isEmpty()) {
            wrapper.eq(SysUser::getRole, role);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(SysUser::getStatus, status);
        }
        // P2-2: 按 DB 最新角色判定（不用 token 角色），降级后旧 AT 窗口内不得继续查看学生列表
        if (UserRole.TEACHER.name().equals(resolveDbRole(currentUserId))) {
            wrapper.eq(SysUser::getCreatedBy, currentUserId);
        }

        IPage<SysUser> userPage = userMapper.selectPage(pageObj, wrapper);
        // 转换为 DTO
        return userPage.convert(sysUserConverter::toDTO);
    }

    /**
     * 更新用户信息（超管不可改角色）
     *
     * @param id         用户 ID
     * @param request    更新请求
     * @param currentUserId 当前操作者 ID
     */
    public UserDTO update(Long id, UpdateUserRequest request, Long currentUserId) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        // 教师只能操作自己创建的学生
        checkTeacherPermission(user, currentUserId);

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        userMapper.updateById(user);

        log.info("更新用户: userId={}, operator={}", id, currentUserId);
        return sysUserConverter.toDTO(user);
    }

    /**
     * 重置密码
     *
     * @param id           用户 ID
     * @param newPassword  新密码（明文）
     * @param currentUserId 当前操作者 ID
     */
    public void resetPassword(Long id, String newPassword, Long currentUserId) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        // 超管可重置任何用户密码，教师只能重置自己创建的学生
        checkTeacherPermission(user, currentUserId);

        LambdaUpdateWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, id)
                .set(SysUser::getPasswordHash, passwordEncoder.encode(newPassword));
        userMapper.update(null, wrapper);

        log.info("重置密码: userId={}, operator={}", id, currentUserId);
    }

    /**
     * 更新用户状态（启用/禁用）
     *
     * <p>超管不可禁用。禁用用户时将其所有活跃 session jti 入黑名单。
     *
     * @param id           用户 ID
     * @param status       新状态（ACTIVE / DISABLED）
     * @param currentUserId 当前操作者 ID
     */
    public void updateStatus(Long id, String status, Long currentUserId) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        // 超管不可禁用
        if (UserRole.SUPER_ADMIN.name().equals(user.getRole()) && "DISABLED".equals(status)) {
            throw new BizException(ErrorCode.FORBIDDEN, "超级管理员不可禁用");
        }

        // 教师只能操作自己创建的学生
        checkTeacherPermission(user, currentUserId);

        LambdaUpdateWrapper<SysUser> wrapper =
                Wrappers.<SysUser>lambdaUpdate().eq(SysUser::getId, id).set(SysUser::getStatus, status);
        userMapper.update(null, wrapper);

        // 禁用用户时，将其所有活跃 session jti 入黑名单
        if ("DISABLED".equals(status)) {
            deviceKickService.disableUser(id, currentUserId);
        }

        log.info("更新用户状态: userId={}, status={}, operator={}", id, status, currentUserId);
    }

    /**
     * 删除用户（软删除 + 级联）
     *
     * <p>超管不可删。级联：sys_login_record → REVOKED, jti → blacklist。
     *
     * @param id            用户 ID
     * @param currentUserId 当前操作者 ID
     */
    public void delete(Long id, Long currentUserId) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        // 超管不可删
        if (UserRole.SUPER_ADMIN.name().equals(user.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "超级管理员不可删除");
        }

        // 教师只能操作自己创建的学生
        checkTeacherPermission(user, currentUserId);

        // 软删除用户
        LambdaUpdateWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, id)
                .set(SysUser::getDeleted, System.currentTimeMillis());
        userMapper.update(null, wrapper);

        // 级联软删 course_teacher（教师删除时，课程保留由超管重分配）
        if (UserRole.TEACHER.name().equals(user.getRole())) {
            LambdaUpdateWrapper<CourseTeacher> ctWrapper = Wrappers.<CourseTeacher>lambdaUpdate()
                    .eq(CourseTeacher::getTeacherId, id)
                    .eq(CourseTeacher::getDeleted, 0)
                    .set(CourseTeacher::getDeleted, System.currentTimeMillis());
            courseTeacherMapper.update(null, ctWrapper);
        }

        // 禁用该用户所有活跃 session
        deviceKickService.disableUser(id, currentUserId);

        // 统计失效：学生数可能已变更（软删后 role=STUDENT 计数减少——先写 DB 后失效，BUG-2 修复）
        dashboardStatsCache.invalidateAll();

        log.info("删除用户: userId={}, operator={}", id, currentUserId);
    }

    /**
     * 教师权限校验：教师只能操作自己创建的学生
     */
    private void checkTeacherPermission(SysUser targetUser, Long currentUserId) {
        SysUser operator = userMapper.selectById(currentUserId);
        if (operator == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "操作者不存在");
        }

        // 超管可操作任何用户
        if (UserRole.SUPER_ADMIN.name().equals(operator.getRole())) {
            return;
        }

        // 教师只能操作学生
        if (UserRole.TEACHER.name().equals(operator.getRole())) {
            if (!UserRole.STUDENT.name().equals(targetUser.getRole())) {
                throw new BizException(ErrorCode.FORBIDDEN, "教师只能操作学生");
            }
            // 教师只能操作自己创建的学生（P0-2d：created_by 归属校验）
            if (targetUser.getCreatedBy() == null || !targetUser.getCreatedBy().equals(currentUserId)) {
                throw new BizException(ErrorCode.FORBIDDEN, "教师只能操作自己创建的学生");
            }
            return;
        }
        // 非超管/教师角色一律拒绝（fail-closed：角色变更后旧 token 不得获得操作权）
        throw new BizException(ErrorCode.FORBIDDEN, "无权操作");
    }

    /**
     * 查询操作者 DB 最新角色（P2-2：create/findPage 的权限判定锚点——
     * 不用 token 角色，角色降级后旧 AT 窗口内立即失效；用户不存在时返回 null（fail-closed））
     *
     * @param operatorId 操作者 ID
     * @return DB 中的最新角色，用户不存在返回 null
     */
    private String resolveDbRole(Long operatorId) {
        if (operatorId == null) {
            return null;
        }
        SysUser operator = userMapper.selectById(operatorId);
        return operator != null ? operator.getRole() : null;
    }

    /**
     * 幂等初始化系统唯一超管账户（默认管理员种子）
     *
     * <p>由 {@link com.commerce.rag.config.AdminSeedInitializer}（ApplicationRunner）在启动时调用，
     * 凭证来自配置（application.yml {@code auth.admin-seed}，env 可覆盖真实值）。
     *
     * <p>幂等语义：
     * <ul>
     *   <li>无未删除超管 → 按配置创建（BCrypt 密文，SUPER_ADMIN / ACTIVE，createdBy=null 种子口径）</li>
     *   <li>已存在超管：密码仍为出厂默认（明文=factoryDefaultPassword，即 V6 预置 admin123）
     *       且与配置不一致 → 刷新为配置值（env 覆盖在此生效）；密码非出厂默认（管理员已改密）→
     *       跳过不覆盖；与配置一致 → 跳过</li>
     *   <li>配置 username 与既有超管不一致时不重命名，仅告警</li>
     * </ul>
     * 重复执行收敛：刷新后哈希与配置一致，后续启动不再写库。
     *
     * @param username               超管登录名（配置值）
     * @param password               超管明文密码（配置值）
     * @param displayName            超管显示名（配置值）
     * @param factoryDefaultPassword 出厂种子默认密码明文（识别"仍未改密"账户的标记）
     */
    @Override
    public void ensureSeedSuperAdmin(
            String username, String password, String displayName, String factoryDefaultPassword) {
        // 1. 查询现有未删除超管（uniq_sys_user_super_admin 索引保证至多 1 条）
        LambdaQueryWrapper<SysUser> queryWrapper =
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getRole, UserRole.SUPER_ADMIN.name());
        SysUser existing = userMapper.selectOne(queryWrapper);

        // 2. 无超管 → 按配置创建（种子账户 createdBy=null，与 V6 注释口径一致）
        if (existing == null) {
            SysUser seed = new SysUser();
            seed.setUsername(username);
            seed.setPasswordHash(passwordEncoder.encode(password));
            seed.setDisplayName(displayName);
            seed.setRole(UserRole.SUPER_ADMIN.name());
            seed.setStatus("ACTIVE");
            userMapper.insert(seed);
            log.info("初始化超管账户: username={}, userId={}", username, seed.getId());
            return;
        }

        // 3. 配置用户名与既有超管不一致 → 不重命名（避免破坏既有登录引用），仅告警
        if (!existing.getUsername().equals(username)) {
            log.warn("配置超管用户名未生效: 既有超管 username={}（配置={}），保持既有用户名", existing.getUsername(), username);
        }

        // 4. 密码仍为出厂默认（未改密）且与配置不同 → 刷新为配置值（env 覆盖生效点）
        boolean stillFactoryDefault = passwordEncoder.matches(factoryDefaultPassword, existing.getPasswordHash());
        boolean sameAsConfigured = passwordEncoder.matches(password, existing.getPasswordHash());
        if (stillFactoryDefault && !sameAsConfigured) {
            LambdaUpdateWrapper<SysUser> updateWrapper = Wrappers.<SysUser>lambdaUpdate()
                    .eq(SysUser::getId, existing.getId())
                    .set(SysUser::getPasswordHash, passwordEncoder.encode(password));
            userMapper.update(null, updateWrapper);
            log.info("刷新超管密码为配置值: username={}", existing.getUsername());
            return;
        }

        // 5. 其余情形（管理员已改密 / 密码与配置一致）→ 跳过，保持幂等
        log.info(
                "超管账户已就绪，跳过种子写入: username={}, 密码非出厂默认={}, 密码与配置一致={}",
                existing.getUsername(),
                !stillFactoryDefault,
                sameAsConfigured);
    }
}
