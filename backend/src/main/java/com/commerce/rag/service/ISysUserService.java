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
}
