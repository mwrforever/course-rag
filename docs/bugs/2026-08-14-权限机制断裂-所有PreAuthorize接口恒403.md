# 权限机制断裂：JWT 鉴权与 Spring Security 未桥接，所有 @PreAuthorize 接口恒 403

- **风险类别**：运行错误（权限体系整体失效，B/C 端管理接口全部不可用）
- **严重度**：P0（阻塞级）
- **变更范围**：未提交工作区（HEAD `0c08d32` 之后全部新增代码）

## 证据

1. `backend/src/main/java/com/commerce/rag/auth/SecurityConfig.java:44-63`：
   ```java
   .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/auth/**")
           .permitAll()
           .requestMatchers("/api/v1/public/**")
           .permitAll()
           .anyRequest()
           .permitAll())
   ```
   过滤链**未注册任何 JWT 认证过滤器**（无 `OncePerRequestFilter`、无 `SecurityContextHolder` 写入），`@EnableMethodSecurity(prePostEnabled = true)` 开启的方法级鉴权没有 Authentication 来源。

2. `backend/src/main/java/com/commerce/rag/auth/AuthInterceptor.java:86-88`：JWT 校验后仅 `request.setAttribute(...)` 注入，**不写 SecurityContext**。

3. 全代码库 grep 证据：`SecurityContextHolder` / `setAuthentication` / `OncePerRequestFilter` 零命中（`grep -rn "SecurityContextHolder\|setAuthentication\|OncePerRequestFilter" backend/src/main/java/` 无结果）。

4. `@PreAuthorize` 使用面：13 个文件（AdminUserController:38、AdminCourseController:37、AdminDocumentController、StudentController:46、FeedbackController:27 等），全部依赖不存在的 SecurityContext 角色。

## 触发路径与影响

任意携带合法 JWT 的请求调用任一受保护端点（如 `POST /api/v1/admin/documents`）：
AuthInterceptor 校验通过 → Spring Security 方法级拦截 `hasAnyRole('SUPER_ADMIN','TEACHER')` 在匿名 SecurityContext 上恒为 false → `AccessDeniedException` → GlobalExceptionHandler 返回 403。

**所有 B 端管理接口 + C 端反馈接口当前对所有人生效地不可用**（功能全断）。同时角色权限体系从未真正生效——一旦修复本 bug 补上 SecurityContext 填充，A5/A6/A7/A8/A9/C6/C12 等水平越权问题（见同目录其他 bug 文件）会立即暴露，需与本 bug 一并修复。

## 建议修复方向

在 AuthInterceptor（或新增 OncePerRequestFilter）中校验通过后写入 `SecurityContextHolder`（`UsernamePasswordAuthenticationToken` + `ROLE_` 前缀角色），并确保过滤器顺序在方法级鉴权之前。
