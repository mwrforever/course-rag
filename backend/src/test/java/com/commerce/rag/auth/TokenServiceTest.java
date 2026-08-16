package com.commerce.rag.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.commerce.rag.properties.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TokenService 单元测试 —— JWT 签发/验证/刷新
 *
 * @author commerce-rag
 */
@DisplayName("TokenService JWT 签发验证测试")
class TokenServiceTest {

    private TokenService tokenService;
    private AuthProperties authProperties;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties(
                "test-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm!!",
                900, // 15min
                604800L, // 7d
                "commerce_token",
                "localhost",
                false,
                List.of("WEB_DESKTOP"));
        tokenService = new TokenService(authProperties);
        signingKey = Keys.hmacShaKeyFor(authProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("generateAccessToken → 签发有效 JWT，含 userId + role + jti")
    void generateAccessToken_validJwt_containsClaims() {
        String token = tokenService.generateAccessToken(123L, "TEACHER", "jti-abc");

        Claims claims = tokenService.validateToken(token);
        assertEquals(123L, tokenService.extractUserId(claims));
        assertEquals("TEACHER", tokenService.extractRole(claims));
        assertEquals("jti-abc", tokenService.extractJti(claims));
        assertEquals("ACCESS", tokenService.extractTokenType(claims));
    }

    @Test
    @DisplayName("generateRefreshToken → 签发有效 JWT，含 userId + jti + type=REFRESH")
    void generateRefreshToken_validJwt_containsClaims() {
        String token = tokenService.generateRefreshToken(456L, "jti-rt-xyz");

        Claims claims = tokenService.validateToken(token);
        assertEquals(456L, tokenService.extractUserId(claims));
        assertEquals("jti-rt-xyz", tokenService.extractJti(claims));
        assertEquals("REFRESH", tokenService.extractTokenType(claims));
    }

    @Test
    @DisplayName("validateToken → 无效 Token 抛出 JwtException")
    void validateToken_invalidToken_throwsJwtException() {
        assertThrows(JwtException.class, () -> tokenService.validateToken("invalid.token.here"));
    }

    @Test
    @DisplayName("validateToken → 篡改后的 Token 抛出 JwtException")
    void validateToken_tamperedToken_throwsJwtException() {
        String token = tokenService.generateAccessToken(123L, "STUDENT", "jti-1");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThrows(JwtException.class, () -> tokenService.validateToken(tampered));
    }

    @Test
    @DisplayName("getAccessTokenExpiry → 返回配置值 900s")
    void getAccessTokenExpiry_returnsConfiguredValue() {
        assertEquals(900, tokenService.getAccessTokenExpiry());
    }

    @Test
    @DisplayName("getRefreshTokenExpiry → 返回配置值 604800s")
    void getRefreshTokenExpiry_returnsConfiguredValue() {
        assertEquals(604800L, tokenService.getRefreshTokenExpiry());
    }

    @Test
    @DisplayName("generateJti → 生成非空唯一字符串")
    void generateJti_returnsNonEmptyUniqueString() {
        String jti1 = tokenService.generateJti();
        String jti2 = tokenService.generateJti();
        assertNotNull(jti1);
        assertFalse(jti1.isEmpty());
        assertNotEquals(jti1, jti2, "每次生成的 jti 应不同");
    }

    @Test
    @DisplayName("getAccessTokenRemainingTtl → 返回正数（未过期）")
    void getAccessTokenRemainingTtl_returnsPositiveForValidToken() {
        String token = tokenService.generateAccessToken(123L, "STUDENT", "jti-1");
        Claims claims = tokenService.validateToken(token);
        long ttl = tokenService.getAccessTokenRemainingTtl(claims);
        assertTrue(ttl > 0, "未过期 Token 的 TTL 应为正数");
        assertTrue(ttl <= 900, "TTL 不应超过配置的最大值");
    }

    // ==================== parseClaimsLoose() 测试 ====================

    /** 构造一个已过期的 ACCESS token（签名合法） */
    private String buildExpiredAccessToken(String jti) {
        return Jwts.builder()
                .subject("123")
                .claim("userId", 123L)
                .claim("role", "STUDENT")
                .claim("type", "ACCESS")
                .id(jti)
                .issuedAt(new Date(System.currentTimeMillis() - 7200_000L))
                .expiration(new Date(System.currentTimeMillis() - 3600_000L))
                .signWith(signingKey)
                .compact();
    }

    @Test
    @DisplayName("parseClaimsLoose → 有效 token 正常返回 Claims")
    void parseClaimsLoose_validToken_returnsClaims() {
        String token = tokenService.generateAccessToken(123L, "STUDENT", "jti-valid");

        Claims claims = tokenService.parseClaimsLoose(token);

        assertNotNull(claims);
        assertEquals("jti-valid", claims.getId());
        assertEquals("ACCESS", claims.get("type", String.class));
    }

    @Test
    @DisplayName("parseClaimsLoose → 过期 token 仍返回 Claims（登出吊销 RT 依赖）")
    void parseClaimsLoose_expiredToken_returnsClaims() {
        String token = buildExpiredAccessToken("jti-expired");

        Claims claims = tokenService.parseClaimsLoose(token);

        assertNotNull(claims);
        assertEquals("jti-expired", claims.getId());
        assertEquals(123L, tokenService.extractUserId(claims));
    }

    @Test
    @DisplayName("parseClaimsLoose → 签名错误 token 抛出 JwtException")
    void parseClaimsLoose_tamperedToken_throwsJwtException() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "another-secret-key-must-be-at-least-256-bits-long-for-hs256!!".getBytes(StandardCharsets.UTF_8));
        String tampered = Jwts.builder()
                .subject("123")
                .claim("type", "ACCESS")
                .id("jti-fake")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 900_000L))
                .signWith(wrongKey)
                .compact();

        assertThrows(JwtException.class, () -> tokenService.parseClaimsLoose(tampered));
    }
}
