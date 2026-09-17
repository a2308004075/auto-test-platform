/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description JWT 认证过滤器
 */
package com.platform.auth.security;

import com.platform.auth.entity.User;
import com.platform.auth.mapper.TokenBlacklistMapper;
import com.platform.auth.mapper.UserMapper;
import com.platform.auth.service.RoleService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 认证过滤器 - 从请求头解析 Token 并设置 SecurityContext
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    /** 系统保留管理员账号 */
    private static final String RESERVED_USERNAME = "superAdmin";
    /** 内置超级管理员角色编码（superAdmin 账号专属，高于 ADMIN） */
    private static final String BUILTIN_ROLE_CODE = "SUPER_ADMIN";
    /** 内置管理员角色编码 */
    private static final String BUILTIN_ADMIN_CODE = "ADMIN";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserMapper userMapper;
    private final TokenBlacklistMapper tokenBlacklistMapper;
    private final RoleService roleService;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   UserMapper userMapper,
                                   TokenBlacklistMapper tokenBlacklistMapper,
                                   RoleService roleService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userMapper = userMapper;
        this.tokenBlacklistMapper = tokenBlacklistMapper;
        this.roleService = roleService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = jwtTokenProvider.parseToken(token);
                if (jwtTokenProvider.isAccessToken(claims)) {
                    String jti = jwtTokenProvider.getJti(claims);
                    // 检查 Token 是否在黑名单中
                    if (tokenBlacklistMapper.existsByTokenJti(jti)) {
                        log.debug("Token 已在黑名单中, jti={}", jti);
                    } else {
                        String userId = jwtTokenProvider.getUserId(claims);
                        User user = userMapper.selectActiveById(userId != null ? Long.valueOf(userId) : null);
                        if (user != null) {
                            // 权限信息从 JWT claims 中获取（登录时已写入 role_code）
                            String role = jwtTokenProvider.getRole(claims);
                            // superAdmin 账号保护：强制使用 SUPER_ADMIN 角色，不受角色管理配置影响
                            if (RESERVED_USERNAME.equalsIgnoreCase(user.getUsername())) {
                                role = BUILTIN_ROLE_CODE;
                            }
                            // 构建权限列表：角色权限 + 业务权限码（供 @PreAuthorize hasAuthority 检查）
                            List<SimpleGrantedAuthority> authorities = buildAuthorities(role, user.getRoleId());
                            UsernamePasswordAuthenticationToken auth =
                                    new UsernamePasswordAuthenticationToken(
                                            user, null, authorities
                                    );
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }
                    }
                }
            } catch (ExpiredJwtException e) {
                log.debug("Token 已过期: {}", e.getMessage());
            } catch (JwtException e) {
                log.debug("Token 无效: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 构建用户的权限列表（角色权限 + 业务权限码）
     *
     * <p>SUPER_ADMIN / ADMIN 内置角色展开为 permission 表全部启用权限编码；
     * 其他角色从 role_permission 表加载已分配的权限编码（含内置角色判断）。</p>
     */
    private List<SimpleGrantedAuthority> buildAuthorities(String role, Long roleId) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        // 角色权限（供 hasRole / hasAnyRole 检查）
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        // 业务权限码（供 hasAuthority 检查）
        List<String> permCodes = (BUILTIN_ROLE_CODE.equals(role) || BUILTIN_ADMIN_CODE.equals(role))
                ? roleService.getAllActivePermissionCodes()
                : roleService.getAuthorityCodesByRoleId(roleId);
        for (String code : permCodes) {
            authorities.add(new SimpleGrantedAuthority(code));
        }
        return authorities;
    }

    /**
     * 从 Authorization 请求头提取 Bearer Token
     */
    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader(AUTHORIZATION_HEADER);
        if (bearer != null && bearer.startsWith(BEARER_PREFIX)) {
            return bearer.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
