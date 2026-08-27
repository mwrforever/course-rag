package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.dto.CreateUserRequest;
import com.commerce.rag.dto.UpdateUserRequest;
import com.commerce.rag.dto.UserDTO;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.record.AuthUserView;

/**
 * 用户管理服务接口 —— 账号 CRUD、密码重置、状态管理（主表 SysUser）
 *
 * @author commerce-rag
 */
public interface ISysUserService extends IService<SysUser> {

    /**
     * 创建用户（教师/学生）
     *
     * @param request  创建请求
     * @param createdBy 操作者 ID
     * @return 用户视图对象
     */
    UserDTO create(CreateUserRequest request, Long createdBy);

    /**
     * 按 ID 查询用户（无权限过滤）
     */
    UserDTO findById(Long id);

    /**
     * 按 ID 查询用户（按操作者与角色过滤）
     */
    UserDTO findById(Long id, Long currentUserId, String operatorRole);

    /**
     * 按用户名查询认证视图（登录校验用，含密码哈希，禁止出 service 边界）
     */
    AuthUserView findAuthViewByUsername(String username);

    /**
     * 按邮箱查询认证视图（V15 起邮箱登录回退路径用，含密码哈希，禁止出 service 边界）
     *
     * @param email 归一化后的绑定邮箱（调用方保证小写）
     * @return 认证视图；不存在时返回 null
     */
    AuthUserView findAuthViewByEmail(String email);

    /**
     * 是否存在绑定该邮箱的未删除用户（注册查重用，只数行数不取列）
     *
     * @param email 归一化后的绑定邮箱
     * @return 存在返回 true（含 DISABLED 用户——禁用账户同样不允许重复占用邮箱）
     */
    boolean existsByEmail(String email);

    /**
     * 是否存在同名未删除用户（自注册生成用户名的唯一性探测用）
     *
     * @param username 候选用户名
     * @return 存在返回 true
     */
    boolean existsByUsername(String username);

    /**
     * 分页查询用户
     */
    IPage<UserDTO> findPage(int page, int size, String role, String status, Long currentUserId);

    /**
     * 更新用户信息
     */
    UserDTO update(Long id, UpdateUserRequest request, Long currentUserId);

    /**
     * 重置密码（管理员操作）
     */
    void resetPassword(Long id, String newPassword, Long currentUserId);

    /**
     * 更新用户状态（ACTIVE/DISABLED）
     */
    void updateStatus(Long id, String status, Long currentUserId);

    /**
     * 删除用户（软删）
     */
    void delete(Long id, Long currentUserId);

    /**
     * 幂等初始化系统唯一超管账户（默认管理员种子，AdminSeedInitializer 启动调用）
     *
     * <p>语义（保障幂等收敛 + env 覆盖生效）：
     * <ol>
     *   <li>无任何未删除超管 → 按配置创建（BCrypt 密文，SUPER_ADMIN / ACTIVE，createdBy=null）</li>
     *   <li>已存在超管且密码仍为出厂默认（明文等于 factoryDefaultPassword）→ 视为未改密的种子账户，
     *       密码刷新为配置值——本例中 V6 迁移预置的 admin123 由此可被 {@code AUTH_ADMIN_SEED_PASSWORD} 覆盖</li>
     *   <li>已存在超管但密码非出厂默认（管理员已自行改密）→ 跳过，绝不覆盖已改密账户</li>
     * </ol>
     * 配置 username 与既有超管不一致时不重命名，仅记录告警。
     *
     * @param username               超管登录名（配置值）
     * @param password               超管明文密码（配置值，env 可覆盖）
     * @param displayName            超管显示名（配置值）
     * @param factoryDefaultPassword 出厂种子默认密码明文（V6 迁移预置，用于识别"仍未改密"的种子账户）
     */
    void ensureSeedSuperAdmin(String username, String password, String displayName, String factoryDefaultPassword);
}
