/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 用户管理服务
 */
package com.platform.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.auth.dto.*;
import com.platform.auth.entity.User;
import com.platform.auth.entity.UserRole;
import com.platform.auth.mapper.UserMapper;
import com.platform.auth.mapper.UserRoleMapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.NotFoundException;
import com.platform.common.response.PageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户管理服务
 */
@Slf4j
@Service
public class UserService {

    private static final String RESERVED_USERNAME = "superAdmin";
    private static final String RESERVED_DISPLAY_NAME = "管理员";
    private static final String RESERVED_SUPER_DISPLAY_NAME = "超级管理员";
    /** 超级管理员角色编码，仅限 superAdmin 账号拥有，不可分配给其他用户 */
    private static final String RESERVED_SUPER_ROLE_CODE = "SUPER_ADMIN";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, UserRoleMapper userRoleMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 分页查询用户列表
     *
     * @param keyword     通用关键词（同时模糊搜索账号和用户名，向后兼容）
     * @param account     独立搜索账号（username）
     * @param displayName 独立搜索用户名（display_name）
     * @param roleId      角色 ID 筛选
     */
    public PageResponse<UserResponse> listUsers(String keyword, String account, String displayName,
                                                 Long roleId, int page, int pageSize) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        // 通用关键词：同时模糊搜索账号和用户名（向后兼容）
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(User::getUsername, keyword)
                    .or().like(User::getDisplayName, keyword));
        }
        // 独立搜索账号
        if (account != null && !account.isEmpty()) {
            wrapper.like(User::getUsername, account);
        }
        // 独立搜索用户名
        if (displayName != null && !displayName.isEmpty()) {
            wrapper.like(User::getDisplayName, displayName);
        }
        if (roleId != null) {
            wrapper.eq(User::getRoleId, roleId);
        }
        // superAdmin 账号始终排在最前，其余按创建时间倒序
        wrapper.last("ORDER BY CASE WHEN username = 'superAdmin' THEN 0 ELSE 1 END, created_at DESC, id ASC");

        Page<User> pageParam = new Page<>(page, pageSize);
        Page<User> result = userMapper.selectPage(pageParam, wrapper);
        List<UserResponse> records = result.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return PageResponse.of(records, result.getTotal(), page, pageSize);
    }

    /**
     * 检查账号是否可用（保留字 + 唯一性，含已禁用账号）
     *
     * <p>供前端新建用户时实时校验调用。
     *
     * @param account 待校验账号
     * @return 可用性校验结果
     */
    public AccountCheckResponse checkAccountAvailable(String account) {
        if (account == null || account.trim().isEmpty()) {
            return new AccountCheckResponse(false, "账号不能为空");
        }
        String trimmed = account.trim();
        if (trimmed.length() < 6 || trimmed.length() > 50) {
            return new AccountCheckResponse(false, "账号长度必须在 6-50 之间");
        }
        if (RESERVED_USERNAME.equalsIgnoreCase(trimmed)) {
            return new AccountCheckResponse(false, "账号不能使用\"superAdmin\"，该账号为系统保留");
        }
        User existing = userMapper.selectByUsernameIncludeInactive(trimmed);
        if (existing != null) {
            return new AccountCheckResponse(false, "账号已存在");
        }
        return new AccountCheckResponse(true, "账号可用");
    }

    /**
     * 创建用户
     */
    @Transactional(rollbackFor = Exception.class)
    public UserResponse createUser(UserCreateRequest request) {
        validateReservedUsername(request.getUsername());
        validateReservedDisplayName(request.getDisplayName());
        validateSuperAdminRole(request.getRoleId());

        // 校验用户名唯一性（包含已禁用账号，避免禁用账号同名时触发数据库约束报错）
        User existing = userMapper.selectByUsernameIncludeInactive(request.getUsername());
        if (existing != null) {
            throw new BusinessException(ErrorCode.USERNAME_DUPLICATE, "账号已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setDisplayName(request.getDisplayName());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRoleId(request.getRoleId());
        user.setIsActive(1);
        userMapper.insert(user);

        log.info("创建用户成功: username={}, roleId={}", user.getUsername(), user.getRoleId());
        return toResponse(user);
    }

    /**
     * 更新用户
     */
    @Transactional(rollbackFor = Exception.class)
    public UserResponse updateUser(Long userId, UserUpdateRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户", userId);
        }

        // superAdmin 账号保护：仅允许 superAdmin 自己编辑自己，其他用户不可编辑 superAdmin 账号
        if (RESERVED_USERNAME.equalsIgnoreCase(user.getUsername())) {
            Long currentUserId = getCurrentUserId();
            if (currentUserId == null || !currentUserId.equals(user.getId())) {
                throw new BusinessException(ErrorCode.ADMIN_PROTECTED, "系统管理员账号不允许其他用户编辑");
            }
            // superAdmin 账号保护：不允许修改角色，确保角色管理配置不影响超级管理员权限
            if (request.getRoleId() != null && !request.getRoleId().equals(user.getRoleId())) {
                throw new BusinessException(ErrorCode.ADMIN_PROTECTED, "系统管理员账号不允许修改角色");
            }
        }

        // 非 superAdmin 用户不可分配 SUPER_ADMIN 角色
        if (request.getRoleId() != null) {
            validateSuperAdminRole(request.getRoleId());
        }

        if (request.getDisplayName() != null && !request.getDisplayName().equals(user.getDisplayName())) {
            validateReservedDisplayName(request.getDisplayName());
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getRoleId() != null) {
            user.setRoleId(request.getRoleId());
        }

        userMapper.updateById(user);
        log.info("更新用户成功: userId={}", userId);
        return toResponse(user);
    }

    /**
     * 删除用户（软删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户", userId);
        }
        if (RESERVED_USERNAME.equalsIgnoreCase(user.getUsername())) {
            throw new BusinessException(ErrorCode.ADMIN_PROTECTED, "系统内置管理员账号不可删除");
        }
        user.setIsActive(0);
        userMapper.updateById(user);
        log.info("删除用户成功: userId={}, username={}", userId, user.getUsername());
    }

    /**
     * 启用/禁用用户
     */
    @Transactional(rollbackFor = Exception.class)
    public UserResponse toggleStatus(Long userId, StatusToggleRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户", userId);
        }
        if (RESERVED_USERNAME.equalsIgnoreCase(user.getUsername()) && Integer.valueOf(0).equals(request.getIsActive())) {
            throw new BusinessException(ErrorCode.ADMIN_PROTECTED, "系统管理员账号不允许禁用");
        }
        user.setIsActive(request.getIsActive());
        userMapper.updateById(user);
        log.info("用户状态变更: userId={}, isActive={}", userId, request.getIsActive());
        return toResponse(user);
    }

    /**
     * 管理员重置用户密码
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long userId, ResetPasswordRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NotFoundException("用户", userId);
        }
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId)
                .set(User::getPasswordHash, passwordEncoder.encode(request.getNewPassword()));
        userMapper.update(null, wrapper);
        log.info("重置密码成功: userId={}", userId);
    }

    /**
     * 根据 ID 查询启用的用户（供 JwtAuthenticationFilter 使用）
     */
    public User findActiveById(Long userId) {
        return userMapper.selectActiveById(userId);
    }

    /**
     * 更新最后登录时间
     */
    public void updateLastLoginAt(Long userId) {
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId)
                .set(User::getLastLoginAt, LocalDateTime.now());
        userMapper.update(null, wrapper);
    }

    private void validateReservedUsername(String username) {
        if (RESERVED_USERNAME.equalsIgnoreCase(username)) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESERVED, "账号 'superAdmin' 为系统保留，不可使用");
        }
    }

    private void validateReservedDisplayName(String displayName) {
        if (RESERVED_DISPLAY_NAME.equals(displayName)
                || RESERVED_SUPER_DISPLAY_NAME.equals(displayName)) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESERVED,
                    "用户名「" + displayName + "」为系统保留，不可使用");
        }
    }

    /**
     * 校验角色是否为 SUPER_ADMIN（超级管理员角色仅限 superAdmin 账号拥有，不可分配给其他用户）
     */
    private void validateSuperAdminRole(Long roleId) {
        if (roleId == null) {
            return;
        }
        UserRole role = userRoleMapper.selectById(roleId);
        if (role != null && RESERVED_SUPER_ROLE_CODE.equals(role.getRoleCode())) {
            throw new BusinessException(ErrorCode.ADMIN_PROTECTED,
                    "超级管理员角色仅限 superAdmin 账号拥有，不可分配给其他用户");
        }
    }

    /**
     * 从 SecurityContext 获取当前登录用户 ID
     */
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) {
            return ((User) auth.getPrincipal()).getId();
        }
        return null;
    }

    private UserResponse toResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setRoleId(user.getRoleId());
        if (user.getRoleId() != null) {
            UserRole role = userRoleMapper.selectById(user.getRoleId());
            if (role != null) {
                response.setRoleName(role.getRoleName());
                response.setRole(role.getRoleCode());
            }
        }
        response.setIsActive(user.getIsActive());
        response.setLastLoginAt(user.getLastLoginAt());
        response.setCreatedAt(user.getCreatedAt());
        return response;
    }
}
