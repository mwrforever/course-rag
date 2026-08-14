package com.commerce.rag.auth;

import com.commerce.rag.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Token 服务 —— JWT 签发/验证/刷新
 *
 * <p>双 Token 体系：
 * <ul>
 *   <li>AT（Access Token）：15min，无状态 JWT，payload 含 userId + role + jti</li>
 *   <li>RT（Refresh Token）：7d，一次性旋转（复用即全量作废）</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final AuthProperties authProperties;
    private final SecretKey signingKey;

    public TokenService(AuthProperties authProperties) {
        this.authProperties = authProperties;
        this.signingKey = Keys.hmacShaKeyFor(authProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 Access Token
     *
     * @param userId 用户 ID
     * @param role   用户角色
     * @param jti    JWT ID
     * @return 签名后的 JWT 字符串
     */
    public String generateAccessToken(Long userId, String role, String jti) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(authProperties.accessTokenExpiry());
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("role", role)
                .claim("type", "ACCESS")
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 生成 Refresh Token
     *
     * @param userId 用户 ID
     * @param jtiRt  RT 的 JWT ID
     * @return 签名后的 JWT 字符串
     */
    public String generateRefreshToken(Long userId, String jtiRt) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(authProperties.refreshTokenExpiry());
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("type", "REFRESH")
                .id(jtiRt)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 验证 Token 并返回 Claims
     *
     * @param token JWT 字符串
     * @return Claims（含 userId, role, jti, type 等）
     * @throws JwtException Token 无效或过期时抛出
     */
    public Claims validateToken(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 宽松解析 Token —— 校验签名，但不校验过期时间
     *
     * <p>登出场景专用：AT 过期后（默认 15min 内）RT（7d）仍有效，
     * 登出必须仍能定位 login_record 吊销 RT。签名始终校验，
     * 伪造 token 抛 SignatureException（JwtException 子类）。
     *
     * @param token JWT 字符串
     * @return Claims（含 userId、jti、type 等）
     * @throws JwtException Token 无效（签名/格式错误）时抛出
     */
    public Claims parseClaimsLoose(String token) throws JwtException {
        try {
            return validateToken(token);
        } catch (ExpiredJwtException e) {
            // 过期 token 允许解析：ExpiredJwtException 携带已解析的 Claims
            return e.getClaims();
        }
    }

    /**
     * 从 Token 提取 userId
     */
    public Long extractUserId(Claims claims) {
        Object userId = claims.get("userId");
        if (userId instanceof Integer) {
            return ((Integer) userId).longValue();
        }
        return (Long) userId;
    }

    /**
     * 从 Token 提取角色
     */
    public String extractRole(Claims claims) {
        return claims.get("role", String.class);
    }

    /**
     * 从 Token 提取 jti
     */
    public String extractJti(Claims claims) {
        return claims.getId();
    }

    /**
     * 从 Token 提取类型（ACCESS / REFRESH）
     */
    public String extractTokenType(Claims claims) {
        return claims.get("type", String.class);
    }

    /**
     * 获取 AT 有效期（秒）
     */
    public int getAccessTokenExpiry() {
        return authProperties.accessTokenExpiry();
    }

    /**
     * 获取 RT 有效期（秒）
     */
    public long getRefreshTokenExpiry() {
        return authProperties.refreshTokenExpiry();
    }

    /**
     * 获取 Access Token 剩余有效期（秒）
     *
     * @param claims 已解析的 Claims
     * @return 剩余秒数（最小 1）
     */
    public long getAccessTokenRemainingTtl(Claims claims) {
        long remaining = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
        return Math.max(remaining, 1);
    }

    /**
     * 获取 Refresh Token 剩余有效期（秒）
     *
     * @param claims 已解析的 Claims
     * @return 剩余秒数（最小 1）
     */
    public long getRefreshTokenRemainingTtl(Claims claims) {
        long remaining = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
        return Math.max(remaining, 1);
    }

    /**
     * 生成随机 jti
     */
    public String generateJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
