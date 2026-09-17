/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 认证服务
 */
package com.platform.auth.service;

import com.platform.auth.dto.*;
import com.platform.auth.entity.GlobalSettings;
import com.platform.auth.entity.LoginLog;
import com.platform.auth.entity.TokenBlacklist;
import com.platform.auth.entity.User;
import com.platform.auth.entity.UserRole;
import com.platform.auth.mapper.GlobalSettingsMapper;
import com.platform.auth.mapper.LoginLogMapper;
import com.platform.auth.mapper.TokenBlacklistMapper;
import com.platform.auth.mapper.UserMapper;
import com.platform.auth.mapper.UserRoleMapper;
import com.platform.auth.security.JwtTokenProvider;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.response.PageResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 认证服务 - 登录、Token 刷新、登出
 */
@Slf4j
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final TokenBlacklistMapper tokenBlacklistMapper;
    private final LoginLogMapper loginLogMapper;
    private final GlobalSettingsMapper globalSettingsMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaService captchaService;
    private final RoleService roleService;

    private static final String RESERVED_USERNAME = "superAdmin";
    private static final String RESERVED_DISPLAY_NAME = "管理员";
    /** superAdmin 账号的显示名（超级管理员） */
    private static final String SUPER_ADMIN_DISPLAY_NAME = "超级管理员";
    /** 内置超级管理员角色编码（superAdmin 账号专属，高于 ADMIN） */
    private static final String BUILTIN_ROLE_CODE = "SUPER_ADMIN";
    /** 登录有效时长配置键（天，全局统一） */
    private static final String CONFIG_LOGIN_VALIDITY_DAYS = "session.login_validity_days";
    /** 登录有效时长回退默认值（天） */
    private static final int DEFAULT_LOGIN_VALIDITY_DAYS = 5;

    public AuthService(UserMapper userMapper,
                       UserRoleMapper userRoleMapper,
                       TokenBlacklistMapper tokenBlacklistMapper,
                       LoginLogMapper loginLogMapper,
                       GlobalSettingsMapper globalSettingsMapper,
                       JwtTokenProvider jwtTokenProvider,
                       PasswordEncoder passwordEncoder,
                       CaptchaService captchaService,
                       RoleService roleService) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.tokenBlacklistMapper = tokenBlacklistMapper;
        this.loginLogMapper = loginLogMapper;
        this.globalSettingsMapper = globalSettingsMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.captchaService = captchaService;
        this.roleService = roleService;
    }

    /**
     * 读取登录有效时长（毫秒）
     *
     * <p>从全局配置 session.login_validity_days 读取（单位：天），对所有用户统一生效；
     * 配置缺失或非法时回退默认 {@value DEFAULT_LOGIN_VALIDITY_DAYS} 天。
     *
     * @return 有效时长（毫秒）
     */
    private long getLoginValidityMs() {
        int days = DEFAULT_LOGIN_VALIDITY_DAYS;
        try {
            GlobalSettings settings = globalSettingsMapper.selectByConfigKey(CONFIG_LOGIN_VALIDITY_DAYS);
            if (settings != null && settings.getConfigValue() != null) {
                int parsed = Integer.parseInt(settings.getConfigValue().trim());
                if (parsed >= 1) {
                    days = parsed;
                } else {
                    log.warn("登录有效时长配置非法: {}，回退默认 {} 天", settings.getConfigValue(), DEFAULT_LOGIN_VALIDITY_DAYS);
                }
            }
        } catch (NumberFormatException e) {
            log.warn("登录有效时长配置解析失败，回退默认 {} 天", DEFAULT_LOGIN_VALIDITY_DAYS);
        }
        return days * 24L * 60L * 60L * 1000L;
    }

    /**
     * 根据 roleId 查询角色编码
     *
     * @param roleId 角色 ID
     * @return 角色编码（如 ADMIN、TESTER），找不到时返回 null
     */
    private String getRoleCode(Long roleId) {
        if (roleId == null) {
            return null;
        }
        UserRole role = userRoleMapper.selectById(roleId);
        return role != null ? role.getRoleCode() : null;
    }

    /**
     * 用户登录
     *
     * <p>流程：验证验证码 → 查询用户 → 检查启用状态 → bcrypt 验证密码 → 生成双 Token → 更新 lastLoginAt
     * <p>同时记录登录日志（成功/失败）
     *
     * @param request   登录请求
     * @param ip        客户端 IP
     * @param userAgent 客户端 User-Agent
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        String browser = parseBrowser(userAgent);
        String os = parseOS(userAgent);

        // 验证码校验
        if (!captchaService.verifyCaptcha(request.getCaptchaId(), request.getCaptchaCode())) {
            recordLoginLog(null, request.getUsername(), "FAILED", ip, userAgent, browser, os, "验证码错误或已过期");
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期");
        }

        User user = userMapper.selectByUsername(request.getUsername());
        if (user == null) {
            recordLoginLog(null, request.getUsername(), "FAILED", ip, userAgent, browser, os, "用户名或密码错误");
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }
        if (Integer.valueOf(0).equals(user.getIsActive())) {
            recordLoginLog(user.getId(), request.getUsername(), "FAILED", ip, userAgent, browser, os, "账号已被禁用");
            throw new BusinessException(ErrorCode.ACCOUNT_RESERVED, "账号已被禁用");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            recordLoginLog(user.getId(), request.getUsername(), "FAILED", ip, userAgent, browser, os, "用户名或密码错误");
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }

        String roleCode = resolveEffectiveRoleCode(user);
        long validityMs = getLoginValidityMs();
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), roleCode, validityMs);
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), validityMs);

        // 更新最后登录时间
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        // 记录登录成功日志
        recordLoginLog(user.getId(), request.getUsername(), "SUCCESS", ip, userAgent, browser, os, null);

        LoginResponse response = new LoginResponse();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setExpiresIn(validityMs / 1000);
        response.setUser(toUserBrief(user));

        log.info("用户登录成功: username={}", user.getUsername());
        return response;
    }

    /**
     * 刷新 Access Token
     *
     * <p>流程：解码 Refresh Token → 验证类型 → 检查黑名单 → 验证用户 → 生成新 Access Token
     */
    public TokenResponse refreshToken(RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parseToken(request.getRefreshToken());
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED, "Refresh Token 已过期");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "无效的 Refresh Token");
        }

        if (!jwtTokenProvider.isRefreshToken(claims)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Token 类型无效");
        }

        // 检查黑名单
        String jti = jwtTokenProvider.getJti(claims);
        if (tokenBlacklistMapper.existsByTokenJti(jti)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED, "Token 已失效");
        }

        // 验证用户有效性
        String userId = jwtTokenProvider.getUserId(claims);
        User user = userMapper.selectActiveById(userId != null ? Long.valueOf(userId) : null);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在或已禁用");
        }

        String roleCode = resolveEffectiveRoleCode(user);
        long validityMs = getLoginValidityMs();
        String newAccessToken = jwtTokenProvider.createAccessToken(user.getId(), roleCode, validityMs);

        TokenResponse response = new TokenResponse();
        response.setAccessToken(newAccessToken);
        response.setExpiresIn(validityMs / 1000);

        log.debug("Token 刷新成功: userId={}", userId);
        return response;
    }

    /**
     * 登出 - 将当前 Token 加入黑名单
     *
     * @param token 当前请求的 Access Token（从 Authorization 头中提取）
     */
    @Transactional(rollbackFor = Exception.class)
    public void logout(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        Claims claims = jwtTokenProvider.tryParseToken(token);
        if (claims == null) {
            return;
        }

        String jti = jwtTokenProvider.getJti(claims);
        Date expiration = jwtTokenProvider.getExpiration(claims);
        String userId = jwtTokenProvider.getUserId(claims);

        TokenBlacklist blacklist = new TokenBlacklist();
        blacklist.setTokenJti(jti);
        blacklist.setUserId(userId != null ? Long.valueOf(userId) : null);
        blacklist.setExpiresAt(LocalDateTime.ofInstant(expiration.toInstant(), ZoneId.systemDefault()));
        tokenBlacklistMapper.insert(blacklist);

        log.info("用户登出: userId={}, jti={}", userId, jti);
    }

    /**
     * 获取当前登录用户信息
     */
    public UserResponse getCurrentUser(Long userId) {
        User user = userMapper.selectActiveById(userId != null ? Long.valueOf(userId) : null);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        return toUserResponse(user);
    }

    /**
     * 更新个人资料（当前用户修改自己的资料）
     *
     * <p>superAdmin 账号不允许修改账号（username）；显示名（displayName）允许修改。
     * 非 admin 用户可修改两者。修改用户名时校验唯一性和保留字。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserResponse updateProfile(Long userId, ProfileUpdateRequest request) {
        User user = userMapper.selectActiveById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }

        boolean isAdmin = RESERVED_USERNAME.equalsIgnoreCase(user.getUsername());

        // superAdmin 账号保护：不允许修改账号（username）
        if (isAdmin) {
            if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
                throw new BusinessException(ErrorCode.ADMIN_PROTECTED, "系统管理员账号不允许修改账号");
            }
        }

        // 更新显示名（仅在值发生变化时校验保留字，允许 superAdmin 保持当前显示名）
        if (request.getDisplayName() != null && !request.getDisplayName().isEmpty()
                && !request.getDisplayName().equals(user.getDisplayName())) {
            if (RESERVED_DISPLAY_NAME.equals(request.getDisplayName())
                    || SUPER_ADMIN_DISPLAY_NAME.equals(request.getDisplayName())) {
                throw new BusinessException(ErrorCode.ACCOUNT_RESERVED,
                        "用户名「" + request.getDisplayName() + "」为系统保留，不可使用");
            }
            user.setDisplayName(request.getDisplayName());
        }

        // 更新账号（用户名）
        if (request.getUsername() != null && !request.getUsername().isEmpty()
                && !request.getUsername().equals(user.getUsername())) {
            if (RESERVED_USERNAME.equalsIgnoreCase(request.getUsername())) {
                throw new BusinessException(ErrorCode.ACCOUNT_RESERVED, "账号 'superAdmin' 为系统保留，不可使用");
            }
            // 唯一性校验需包含已禁用账号，避免禁用账号同名时触发数据库约束报错
            User existing = userMapper.selectByUsernameIncludeInactive(request.getUsername());
            if (existing != null) {
                throw new BusinessException(ErrorCode.USERNAME_DUPLICATE, "账号已存在");
            }
            user.setUsername(request.getUsername());
        }

        // 更新个人简介
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }

        userMapper.updateById(user);
        log.info("更新个人资料成功: userId={}", userId);
        return toUserResponse(user);
    }

    /**
     * 修改密码（当前用户修改自己的密码）
     *
     * <p>验证当前密码 → 编码新密码 → 更新 passwordHash
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userMapper.selectActiveById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_INCORRECT, "当前密码错误");
        }
        if (request.getCurrentPassword().equals(request.getNewPassword())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "新密码不能与当前密码相同");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(user);
        log.info("修改密码成功: userId={}", userId);
    }

    /**
     * 查询当前用户的登录日志（最近 30 天，分页）
     */
    public PageResponse<LoginLogResponse> getLoginLogs(Long userId, int page, int pageSize) {
        LocalDateTime since = LocalDateTime.now().minusDays(30);

        LambdaQueryWrapper<LoginLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LoginLog::getUserId, userId)
                .ge(LoginLog::getCreatedAt, since)
                .orderByDesc(LoginLog::getCreatedAt);

        Page<LoginLog> pageParam = new Page<>(page, pageSize);
        Page<LoginLog> result = loginLogMapper.selectPage(pageParam, wrapper);
        List<LoginLogResponse> records = result.getRecords().stream()
                .map(this::toLoginLogResponse)
                .collect(Collectors.toList());
        return PageResponse.of(records, result.getTotal(), page, pageSize);
    }

    /**
     * 记录登录日志
     */
    private void recordLoginLog(Long userId, String username, String status,
                                String ip, String userAgent, String browser, String os, String message) {
        try {
            LoginLog logEntry = new LoginLog();
            logEntry.setUserId(userId);
            logEntry.setUsername(username);
            logEntry.setStatus(status);
            logEntry.setIp(ip);
            logEntry.setUserAgent(userAgent);
            logEntry.setBrowser(browser);
            logEntry.setOs(os);
            logEntry.setMessage(message);
            loginLogMapper.insert(logEntry);
        } catch (Exception e) {
            // 登录日志记录失败不应影响登录流程
            log.warn("记录登录日志失败: username={}, status={}", username, status, e);
        }
    }

    /**
     * 从 User-Agent 解析浏览器名称
     */
    private String parseBrowser(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return "Unknown";
        }
        return UserAgentUtil.parse(userAgent).getBrowser().toString();
    }

    /**
     * 从 User-Agent 解析操作系统
     */
    private String parseOS(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return "Unknown";
        }
        return UserAgentUtil.parse(userAgent).getOs().toString();
    }

    private LoginLogResponse toLoginLogResponse(LoginLog log) {
        LoginLogResponse response = new LoginLogResponse();
        response.setId(log.getId());
        response.setStatus(log.getStatus());
        response.setIp(log.getIp());
        response.setBrowser(log.getBrowser());
        response.setOs(log.getOs());
        response.setMessage(log.getMessage());
        response.setCreatedAt(log.getCreatedAt());
        return response;
    }

    private UserResponse toUserResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setBio(user.getBio());
        response.setRoleId(user.getRoleId());
        // superAdmin 账号保护：强制使用 SUPER_ADMIN 角色和全部权限
        if (isSuperAdmin(user)) {
            response.setRole(BUILTIN_ROLE_CODE);
            response.setRoleName(SUPER_ADMIN_DISPLAY_NAME);
            response.setPermissions(Collections.singletonList("*"));
            response.setPermissionDetails(Collections.singletonList(superAdminPermissionDetail()));
        } else {
            String roleCode = getRoleCode(user.getRoleId());
            response.setRole(roleCode);
            UserRole role = userRoleMapper.selectById(user.getRoleId());
            if (role != null) {
                response.setRoleName(role.getRoleName());
            }
            response.setPermissions(roleService.getPermissionCodesByRoleId(user.getRoleId()));
            response.setPermissionDetails(roleService.getPermissionDetailsByRoleId(user.getRoleId()));
        }
        response.setIsActive(user.getIsActive());
        response.setLastLoginAt(user.getLastLoginAt());
        response.setCreatedAt(user.getCreatedAt());
        return response;
    }

    private UserBriefDTO toUserBrief(User user) {
        UserBriefDTO brief = new UserBriefDTO();
        brief.setId(user.getId());
        brief.setUsername(user.getUsername());
        brief.setDisplayName(user.getDisplayName());
        // superAdmin 账号保护：强制使用 SUPER_ADMIN 角色和全部权限
        if (isSuperAdmin(user)) {
            brief.setRole(BUILTIN_ROLE_CODE);
            brief.setPermissions(Collections.singletonList("*"));
            brief.setPermissionDetails(Collections.singletonList(superAdminPermissionDetail()));
        } else {
            brief.setRole(getRoleCode(user.getRoleId()));
            brief.setPermissions(roleService.getPermissionCodesByRoleId(user.getRoleId()));
            brief.setPermissionDetails(roleService.getPermissionDetailsByRoleId(user.getRoleId()));
        }
        return brief;
    }

    private boolean isSuperAdmin(User user) {
        return RESERVED_USERNAME.equalsIgnoreCase(user.getUsername());
    }

    /**
     * 解析用户的有效角色编码，superAdmin 强制使用 SUPER_ADMIN
     */
    private String resolveEffectiveRoleCode(User user) {
        if (isSuperAdmin(user)) {
            return BUILTIN_ROLE_CODE;
        }
        return getRoleCode(user.getRoleId());
    }

    private PermissionBriefDTO superAdminPermissionDetail() {
        PermissionBriefDTO dto = new PermissionBriefDTO();
        dto.setCode("*");
        return dto;
    }
}
