package com.platform.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.security.crypto.BCryptUtils;
import com.platform.security.jwt.JwtProperties;
import com.platform.security.jwt.JwtUtils;
import com.platform.security.jwt.TokenPair;
import com.platform.user.dto.LoginRequest;
import com.platform.user.dto.RegisterRequest;
import com.platform.user.entity.ApiKey;
import com.platform.user.entity.Tenant;
import com.platform.user.entity.UserAccount;
import com.platform.user.entity.UserTenant;
import com.platform.user.mapper.ApiKeyMapper;
import com.platform.user.mapper.TenantMapper;
import com.platform.user.mapper.UserAccountMapper;
import com.platform.user.mapper.UserTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认证服务：登录、注册、刷新 Token、登出、API Key 管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountMapper userAccountMapper;
    private final TenantMapper tenantMapper;
    private final UserTenantMapper userTenantMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final BCryptUtils bcryptUtils;
    private final JwtUtils jwtUtils;
    private final JwtProperties jwtProperties;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String REFRESH_BLACKLIST_PREFIX = "auth:refresh:blacklist:";

    // ==================== 登录 ====================

    /**
     * 用户名 + 密码登录，返回双 Token。
     */
    public TokenPair login(LoginRequest request, String ip) {
        // 1. 查找用户
        UserAccount user = userAccountMapper.selectOne(
                new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getUsername, request.getUsername())
        );
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 2. 校验状态
        if (UserAccount.STATUS_DISABLED.equals(user.getStatus())) {
            throw new BizException(ErrorCode.NO_PERMISSION, "账户已被禁用");
        }
        if (UserAccount.STATUS_LOCKED.equals(user.getStatus())) {
            throw new BizException(ErrorCode.NO_PERMISSION, "账户已被锁定");
        }

        // 3. 验证密码
        if (!bcryptUtils.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "用户名或密码错误");
        }

        // 4. 查找用户-租户关联，取第一个
        List<UserTenant> userTenants = userTenantMapper.selectList(
                new LambdaQueryWrapper<UserTenant>().eq(UserTenant::getUserId, user.getId())
        );
        String tenantId = userTenants.isEmpty() ? "" : userTenants.get(0).getTenantId();
        List<String> roles = userTenants.stream()
                .map(UserTenant::getRoleCode)
                .distinct()
                .toList();

        // 5. 生成 Token
        String accessToken = jwtUtils.generateAccessToken(user.getId(), user.getUsername(), tenantId, roles);
        String refreshToken = jwtUtils.generateRefreshToken(user.getId());

        // 6. 更新登录信息
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(ip);
        userAccountMapper.updateById(user);

        return new TokenPair(accessToken, refreshToken, jwtProperties.getAccessTokenTtl());
    }

    // ==================== 注册 ====================

    /**
     * 注册新用户，自动创建个人租户并分配 TENANT_ADMIN 角色。
     */
    @Transactional
    public UserAccount register(RegisterRequest request) {
        // 1. 校验用户名唯一性
        Long count = userAccountMapper.selectCount(
                new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getUsername, request.getUsername())
        );
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户名已存在");
        }

        // 2. 创建用户
        UserAccount user = new UserAccount();
        user.setUsername(request.getUsername());
        user.setPasswordHash(bcryptUtils.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(UserAccount.STATUS_ACTIVE);
        userAccountMapper.insert(user);

        // 3. 创建个人租户
        Tenant tenant = new Tenant();
        tenant.setName(request.getUsername() + "的工作空间");
        tenant.setStatus(Tenant.STATUS_ACTIVE);
        tenant.setStorageProvider("minio");
        tenant.setQuotaConfig("{}");
        tenantMapper.insert(tenant);

        // 4. 关联用户与租户
        UserTenant userTenant = new UserTenant();
        userTenant.setUserId(user.getId());
        userTenant.setTenantId(tenant.getId());
        userTenant.setRoleCode(UserTenant.ROLE_TENANT_ADMIN);
        userTenantMapper.insert(userTenant);

        log.info("New user registered: username={}, userId={}, tenantId={}", user.getUsername(), user.getId(), tenant.getId());
        return user;
    }

    // ==================== Token 刷新 ====================

    /**
     * 刷新 Access Token（Refresh Token 轮转）。
     */
    public TokenPair refresh(String refreshToken) {
        // 1. 校验 Refresh Token（必须为 type=refresh，access token 不可当 refresh 用）
        if (!jwtUtils.validateToken(refreshToken)) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED);
        }
        if (!"refresh".equals(jwtUtils.parseClaims(refreshToken).get("type", String.class))) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        String userId = jwtUtils.getUserId(refreshToken);

        // 2. 检查黑名单（按 jti 维度：只撤销被轮转/登出的那一个 token，不影响该用户其他会话）
        String blacklistKey = REFRESH_BLACKLIST_PREFIX + jwtUtils.getJti(refreshToken);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(blacklistKey))) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "Refresh Token 已被撤销");
        }

        // 3. 重新查用户和角色
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        List<UserTenant> userTenants = userTenantMapper.selectList(
                new LambdaQueryWrapper<UserTenant>().eq(UserTenant::getUserId, userId)
        );
        String tenantId = userTenants.isEmpty() ? "" : userTenants.get(0).getTenantId();
        List<String> roles = userTenants.stream().map(UserTenant::getRoleCode).distinct().toList();

        // 4. 生成新 Token 对
        String newAccessToken = jwtUtils.generateAccessToken(userId, user.getUsername(), tenantId, roles);
        String newRefreshToken = jwtUtils.generateRefreshToken(userId);

        // 5. 当前 Refresh Token 加入黑名单（按 jti 维度，新 token 不受影响）
        stringRedisTemplate.opsForValue().set(blacklistKey, "1", Duration.ofSeconds(jwtProperties.getRefreshTokenTtl()));

        return new TokenPair(newAccessToken, newRefreshToken, jwtProperties.getAccessTokenTtl());
    }

    // ==================== 登出 ====================

    /**
     * 登出，将当前 Refresh Token（按 jti）加入黑名单。
     */
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            return;
        }
        String jti;
        try {
            jti = jwtUtils.getJti(refreshToken);
        } catch (Exception e) {
            return;
        }
        if (jti == null || jti.isBlank()) {
            return;
        }
        String blacklistKey = REFRESH_BLACKLIST_PREFIX + jti;
        stringRedisTemplate.opsForValue().set(blacklistKey, "1", Duration.ofSeconds(jwtProperties.getRefreshTokenTtl()));
    }

    // ==================== API Key 管理 ====================

    /**
     * 创建 API Key。
     */
    @Transactional
    public String createApiKey(String userId, String tenantId, String name, Integer rateLimitQpm, LocalDateTime expiresAt) {
        String rawKey = "ak_" + UUID.randomUUID().toString().replace("-", "");
        String keyHash = bcryptUtils.encode(rawKey);
        String keyPrefix = rawKey.substring(0, 12);

        ApiKey apiKey = new ApiKey();
        apiKey.setUserId(userId);
        apiKey.setTenantId(tenantId);
        apiKey.setKeyHash(keyHash);
        apiKey.setKeyPrefix(keyPrefix);
        apiKey.setName(name);
        apiKey.setRateLimitQpm(rateLimitQpm != null ? rateLimitQpm : 60);
        apiKey.setExpiresAt(expiresAt);
        apiKey.setStatus(ApiKey.STATUS_ACTIVE);
        apiKeyMapper.insert(apiKey);

        // 返回完整 Key（仅此一次可见）
        return rawKey;
    }

    /**
     * 获取用户的 API Key 列表（仅返回前缀，不返回完整 Key）。
     */
    public List<ApiKey> listApiKeys(String userId, String tenantId) {
        return apiKeyMapper.selectList(
                new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getUserId, userId)
                        .eq(ApiKey::getTenantId, tenantId)
        );
    }

    /**
     * 校验 API Key（供网关调用）：按前缀定位候选行，bcrypt 比对，检查状态与过期。
     * 返回 key 对应的 userId/tenantId（网关据此注入身份头）。
     */
    public Map<String, String> validateApiKey(String rawKey) {
        if (rawKey == null || rawKey.length() < 12) {
            throw new BizException(ErrorCode.API_KEY_INVALID);
        }
        String prefix = rawKey.substring(0, 12);
        List<ApiKey> candidates = apiKeyMapper.selectList(
                new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getKeyPrefix, prefix)
                        .eq(ApiKey::getStatus, ApiKey.STATUS_ACTIVE));
        for (ApiKey candidate : candidates) {
            if (bcryptUtils.matches(rawKey, candidate.getKeyHash())) {
                if (candidate.getExpiresAt() != null && candidate.getExpiresAt().isBefore(LocalDateTime.now())) {
                    throw new BizException(ErrorCode.API_KEY_EXPIRED);
                }
                candidate.setLastUsedAt(LocalDateTime.now());
                apiKeyMapper.updateById(candidate);
                return Map.of("userId", candidate.getUserId(), "tenantId", candidate.getTenantId());
            }
        }
        throw new BizException(ErrorCode.API_KEY_INVALID);
    }

    /**
     * 撤销 API Key。
     */
    public void revokeApiKey(String apiKeyId, String userId) {
        ApiKey apiKey = apiKeyMapper.selectById(apiKeyId);
        if (apiKey == null || !apiKey.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.API_KEY_INVALID);
        }
        apiKey.setStatus(ApiKey.STATUS_REVOKED);
        apiKeyMapper.updateById(apiKey);
    }
}
