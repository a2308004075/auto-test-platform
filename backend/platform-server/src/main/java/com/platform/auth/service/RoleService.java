/**
 * @author HXN
 * @date 2026-08-22 13:28
 * @description 角色管理服务
 */
package com.platform.auth.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.auth.dto.*;
import com.platform.auth.entity.Permission;
import com.platform.auth.entity.RolePermission;
import com.platform.auth.entity.User;
import com.platform.auth.entity.UserRole;
import com.platform.auth.mapper.PermissionMapper;
import com.platform.auth.mapper.RolePermissionMapper;
import com.platform.auth.mapper.UserMapper;
import com.platform.auth.mapper.UserRoleMapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.NotFoundException;
import com.platform.common.response.PageResponse;
import com.platform.sys.entity.Dict;
import com.platform.sys.entity.Menu;
import com.platform.sys.mapper.DictMapper;
import com.platform.sys.mapper.MenuMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 角色管理服务
 *
 * <p>提供角色 CRUD、权限分配、Excel 导入导出等功能。
 * SUPER_ADMIN 和 ADMIN 角色为系统内置，不可删除/禁用/修改权限，且隐式拥有全部权限。
 */
@Slf4j
@Service
public class RoleService {

    private static final String BUILTIN_ROLE_CODE = "ADMIN";
    /** 内置超级管理员角色编码（superAdmin 账号专属，高于 ADMIN） */
    private static final String BUILTIN_SUPER_ROLE_CODE = "SUPER_ADMIN";
    /** 系统保留管理员账号 */
    private static final String RESERVED_USERNAME = "superAdmin";

    /** 角色字典类型编码，与 sys_dict 表中 user_role 字典对应 */
    private static final String ROLE_DICT_TYPE = "user_role";
    private static final String ROLE_DICT_TYPE_NAME = "用户角色";

    private final UserRoleMapper userRoleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserMapper userMapper;
    private final DictMapper dictMapper;
    private final MenuMapper menuMapper;

    public RoleService(UserRoleMapper userRoleMapper,
                       PermissionMapper permissionMapper,
                       RolePermissionMapper rolePermissionMapper,
                       UserMapper userMapper,
                       DictMapper dictMapper,
                       MenuMapper menuMapper) {
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userMapper = userMapper;
        this.dictMapper = dictMapper;
        this.menuMapper = menuMapper;
    }

    // ===== 公共接口 =====

    /**
     * 查询全部启用的角色列表（供下拉框使用）
     * <p>过滤 SUPER_ADMIN：超级管理员仅限 superAdmin 账号拥有，不可分配给其他用户。</p>
     */
    public List<UserRole> listActiveRoles() {
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(UserRole::getRoleCode, BUILTIN_SUPER_ROLE_CODE)
                .orderByAsc(UserRole::getSortOrder)
                .orderByAsc(UserRole::getCreatedAt);
        return userRoleMapper.selectList(wrapper);
    }

    // ===== 管理接口 =====

    /**
     * 分页查询角色列表
     */
    public PageResponse<RoleResponse> listRolesPage(int page, int pageSize, String keyword) {
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(UserRole::getRoleName, keyword)
                    .or().like(UserRole::getRoleCode, keyword));
        }
        wrapper.orderByAsc(UserRole::getSortOrder)
                .orderByAsc(UserRole::getCreatedAt);

        Page<UserRole> pageParam = new Page<>(page, pageSize);
        Page<UserRole> result = userRoleMapper.selectPage(pageParam, wrapper);
        List<RoleResponse> records = result.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return PageResponse.of(records, result.getTotal(), page, pageSize);
    }

    /**
     * 查询角色详情（含权限分配列表）
     */
    public RoleResponse getRoleDetail(Long id) {
        UserRole role = userRoleMapper.selectById(id);
        if (role == null) {
            throw new NotFoundException("角色", id);
        }
        RoleResponse response = toResponse(role);
        response.setPermissions(isBuiltinRoleCode(role.getRoleCode())
                ? buildFullPermissionAssignments()
                : rolePermissionMapper.selectPermissionAssignmentsByRoleId(id));
        return response;
    }

    /**
     * 创建角色
     */
    @Transactional(rollbackFor = Exception.class)
    public RoleResponse createRole(RoleCreateRequest request) {
        // 校验角色编码唯一性
        checkRoleCodeDuplicate(request.getRoleCode(), null);

        UserRole role = new UserRole();
        role.setRoleName(request.getRoleName());
        role.setRoleCode(request.getRoleCode().toUpperCase());
        role.setDescription(request.getDescription());
        role.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        role.setIsActive(1);
        userRoleMapper.insert(role);

        // 分配权限
        if (CollUtil.isNotEmpty(request.getPermissions())) {
            bindPermissions(role.getId(), request.getPermissions());
        }

        // 同步字典
        syncDictOnCreate(role);

        log.info("创建角色成功: roleCode={}", role.getRoleCode());
        return getRoleDetail(role.getId());
    }

    /**
     * 更新角色
     */
    @Transactional(rollbackFor = Exception.class)
    public RoleResponse updateRole(Long id, RoleCreateRequest request) {
        UserRole role = userRoleMapper.selectById(id);
        if (role == null) {
            throw new NotFoundException("角色", id);
        }
        checkBuiltinRole(role);

        // 校验角色编码唯一性
        checkRoleCodeDuplicate(request.getRoleCode(), id);

        // 记录更新前的角色编码，用于同步字典
        String oldRoleCode = role.getRoleCode();

        role.setRoleName(request.getRoleName());
        role.setRoleCode(request.getRoleCode().toUpperCase());
        role.setDescription(request.getDescription());
        if (request.getSortOrder() != null) {
            role.setSortOrder(request.getSortOrder());
        }
        userRoleMapper.updateById(role);

        // 同步字典
        syncDictOnUpdate(oldRoleCode, role);

        // 重建权限关联
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, id));
        if (CollUtil.isNotEmpty(request.getPermissions())) {
            bindPermissions(id, request.getPermissions());
        }

        log.info("更新角色成功: id={}, roleCode={}", id, role.getRoleCode());
        return getRoleDetail(id);
    }

    /**
     * 删除角色（软删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        UserRole role = userRoleMapper.selectById(id);
        if (role == null) {
            throw new NotFoundException("角色", id);
        }
        checkBuiltinRole(role);

        // 检查是否有用户引用此角色
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getRoleId, id));
        if (userCount > 0) {
            throw new BusinessException(ErrorCode.ROLE_HAS_USERS,
                    "该角色下还有 " + userCount + " 个用户，无法删除");
        }

        // 删除权限关联
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, id));

        // 同步字典
        syncDictOnDelete(role);

        // 软删除角色
        userRoleMapper.deleteById(id);
        log.info("删除角色成功: id={}, roleCode={}", id, role.getRoleCode());
    }

    /**
     * 切换角色状态（启用/停用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void toggleRoleStatus(Long id, Integer isActive) {
        UserRole role = userRoleMapper.selectById(id);
        if (role == null) {
            throw new NotFoundException("角色", id);
        }
        checkBuiltinRole(role);
        role.setIsActive(isActive);
        userRoleMapper.updateById(role);

        // 同步字典
        syncDictOnToggleStatus(role, isActive);

        log.info("角色状态变更: id={}, isActive={}", id, isActive);
    }

    // ===== 权限管理 =====

    /**
     * 获取全量权限树
     */
    public List<PermissionTreeNode> getPermissionTree() {
        LambdaQueryWrapper<Permission> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(Permission::getSortOrder)
                .orderByAsc(Permission::getId);
        List<Permission> allPermissions = permissionMapper.selectList(wrapper);
        return buildTree(allPermissions);
    }

    /**
     * 获取角色已分配的权限列表（含按角色 control_mode）
     *
     * <p>SUPER_ADMIN 和 ADMIN 内置角色隐式持有全部权限（不存储于 role_permission 表），
     * 直接由 permission 表全量构造，保证角色管理页面的权限树展示与实际授权一致。</p>
     */
    public List<PermissionAssignmentDTO> getRolePermissions(Long roleId) {
        if (roleId == null) {
            return Collections.emptyList();
        }
        UserRole role = userRoleMapper.selectById(roleId);
        if (role == null) {
            return Collections.emptyList();
        }
        if (isBuiltinRoleCode(role.getRoleCode())) {
            return buildFullPermissionAssignments();
        }
        return rolePermissionMapper.selectPermissionAssignmentsByRoleId(roleId);
    }

    /**
     * 判断角色编码是否为内置角色（SUPER_ADMIN / ADMIN）
     */
    private boolean isBuiltinRoleCode(String roleCode) {
        return BUILTIN_ROLE_CODE.equalsIgnoreCase(roleCode)
                || BUILTIN_SUPER_ROLE_CODE.equalsIgnoreCase(roleCode);
    }

    /**
     * 构造全量权限分配列表（内置角色隐式持有全部权限）
     *
     * <p>MENU 类型 controlMode 为 null，BUTTON 类型固定 enabled
     * （内置角色的按钮全部可点击，不依赖 role_permission 表）。</p>
     */
    private List<PermissionAssignmentDTO> buildFullPermissionAssignments() {
        LambdaQueryWrapper<Permission> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(Permission::getSortOrder)
                .orderByAsc(Permission::getId);
        List<Permission> allPermissions = permissionMapper.selectList(wrapper);
        return allPermissions.stream().map(p -> {
            PermissionAssignmentDTO dto = new PermissionAssignmentDTO();
            dto.setPermissionId(p.getId());
            dto.setControlMode("BUTTON".equalsIgnoreCase(p.getType()) ? "enabled" : null);
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 分配权限（先删后插，含按角色 control_mode）
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<PermissionAssignmentDTO> permissions) {
        UserRole role = userRoleMapper.selectById(roleId);
        if (role == null) {
            throw new NotFoundException("角色", roleId);
        }
        checkBuiltinRole(role);

        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRoleId, roleId));
        if (CollUtil.isNotEmpty(permissions)) {
            bindPermissions(roleId, permissions);
        }
        log.info("角色权限分配成功: roleId={}, permissionCount={}", roleId, permissions.size());
    }

    /**
     * 获取角色的权限编码列表（供 AuthService 调用）
     * SUPER_ADMIN 和 ADMIN 角色返回 ["*"] 表示拥有全部权限
     */
    public List<String> getPermissionCodesByRoleId(Long roleId) {
        if (roleId == null) {
            return Collections.emptyList();
        }
        UserRole role = userRoleMapper.selectById(roleId);
        if (role == null) {
            return Collections.emptyList();
        }
        if (BUILTIN_ROLE_CODE.equalsIgnoreCase(role.getRoleCode())
                || BUILTIN_SUPER_ROLE_CODE.equalsIgnoreCase(role.getRoleCode())) {
            return Collections.singletonList("*");
        }
        return rolePermissionMapper.selectPermissionCodesByRoleId(roleId);
    }

    /**
     * 获取角色可用于后端鉴权的权限编码列表
     *
     * <p>面向 Spring Security hasAuthority 校验：SUPER_ADMIN 和 ADMIN 内置角色
     * 展开为 permission 表全部启用权限编码（等价于前端通配符 "*" 语义），
     * 其他角色返回 role_permission 实际分配的权限编码。
     * 与 {@link #getPermissionCodesByRoleId(Long)}（返回 "*" 供前端匹配）不同。
     */
    public List<String> getAuthorityCodesByRoleId(Long roleId) {
        if (roleId == null) {
            return Collections.emptyList();
        }
        UserRole role = userRoleMapper.selectById(roleId);
        if (role == null) {
            return Collections.emptyList();
        }
        if (BUILTIN_ROLE_CODE.equalsIgnoreCase(role.getRoleCode())
                || BUILTIN_SUPER_ROLE_CODE.equalsIgnoreCase(role.getRoleCode())) {
            return getAllActivePermissionCodes();
        }
        return rolePermissionMapper.selectPermissionCodesByRoleId(roleId);
    }

    /**
     * 查询全部启用权限编码（superAdmin 保留账号强制全量鉴权用）
     */
    public List<String> getAllActivePermissionCodes() {
        return permissionMapper.selectAllActivePermissionCodes();
    }

    /**
     * 获取角色的权限详情列表（含按角色 control_mode，供前端 v-permission 指令使用）
     * SUPER_ADMIN 和 ADMIN 角色返回通配符 "*" 详情
     */
    public List<PermissionBriefDTO> getPermissionDetailsByRoleId(Long roleId) {
        if (roleId == null) {
            return Collections.emptyList();
        }
        UserRole role = userRoleMapper.selectById(roleId);
        if (role == null) {
            return Collections.emptyList();
        }
        if (BUILTIN_ROLE_CODE.equalsIgnoreCase(role.getRoleCode())
                || BUILTIN_SUPER_ROLE_CODE.equalsIgnoreCase(role.getRoleCode())) {
            PermissionBriefDTO dto = new PermissionBriefDTO();
            dto.setCode("*");
            return Collections.singletonList(dto);
        }
        return rolePermissionMapper.selectPermissionBriefsByRoleId(roleId);
    }

    /**
     * 同步权限：从 sys_menu 表同步页面和按钮到 permission 表
     *
     * <p>同步逻辑：
     * <ul>
     *     <li>扫描 sys_menu 表中所有设置了 permission_code 的菜单条目</li>
     *     <li>menu_type=1(目录) 和 menu_type=2(菜单) 同步为 MENU 类型权限</li>
     *     <li>menu_type=3(按钮) 同步为 BUTTON 类型权限（control_mode 默认 display）</li>
     *     <li>权限不存在则创建，已存在则更新名称/路径/排序号/父级</li>
     *     <li>父级关系通过 permission_code 关联解析</li>
     *     <li>不会删除已有权限</li>
     * </ul>
     *
     * @return 同步结果统计
     */
    @Transactional(rollbackFor = Exception.class)
    public PermissionSyncResult syncPermissions() {
        PermissionSyncResult result = new PermissionSyncResult();

        // 1. 读取所有 sys_menu 条目（按排序号和 ID 升序，确保父级先于子级处理）
        LambdaQueryWrapper<Menu> menuWrapper = new LambdaQueryWrapper<>();
        menuWrapper.orderByAsc(Menu::getSortNo)
                .orderByAsc(Menu::getId);
        List<Menu> menus = menuMapper.selectList(menuWrapper);

        // 2. 读取所有已有权限（仅活跃状态，@TableLogic 自动过滤）
        LambdaQueryWrapper<Permission> permWrapper = new LambdaQueryWrapper<>();
        permWrapper.orderByAsc(Permission::getSortOrder)
                .orderByAsc(Permission::getId);
        List<Permission> existingPerms = permissionMapper.selectList(permWrapper);

        // 3. 构建 permission_code → Permission 映射
        Map<String, Permission> codeToPerm = new LinkedHashMap<>();
        for (Permission p : existingPerms) {
            codeToPerm.put(p.getPermissionCode(), p);
        }

        // 4. 构建 sys_menu id → permission_code 映射（用于解析父级）
        Map<Long, String> menuIdToCode = new HashMap<>();
        for (Menu menu : menus) {
            if (menu.getPermissionCode() != null && !menu.getPermissionCode().isEmpty()) {
                menuIdToCode.put(menu.getId(), menu.getPermissionCode());
            }
        }

        // 5. 逐条处理 sys_menu，同步到 permission 表
        for (Menu menu : menus) {
            String permCode = menu.getPermissionCode();
            if (permCode == null || permCode.isEmpty()) {
                result.setSkippedCount(result.getSkippedCount() + 1);
                continue;
            }

            // 解析父级 permission ID
            Long parentId = resolveParentPermissionId(menu, menuIdToCode, codeToPerm);

            // 判断权限类型
            String permType = (menu.getMenuType() != null && menu.getMenuType() == 3)
                    ? "BUTTON" : "MENU";

            Permission existing = codeToPerm.get(permCode);
            if (existing == null) {
                // 创建新权限
                Permission perm = new Permission();
                perm.setPermissionName(menu.getName());
                perm.setPermissionCode(permCode);
                perm.setType(permType);
                perm.setParentId(parentId);
                perm.setPath("BUTTON".equals(permType) ? null : menu.getRoutePath());
                perm.setSortOrder(menu.getSortNo() != null ? menu.getSortNo() : 0);
                perm.setIsActive(1);
                perm.setControlMode("BUTTON".equals(permType) ? "display" : null);
                permissionMapper.insert(perm);
                codeToPerm.put(permCode, perm);
                result.setCreatedCount(result.getCreatedCount() + 1);
                result.getCreatedNames().add(menu.getName());
                log.info("同步权限-新增: code={}, name={}, type={}", permCode, menu.getName(), permType);
            } else {
                // 更新已有权限（仅当字段发生变化时）
                boolean changed = false;
                if (!menu.getName().equals(existing.getPermissionName())) {
                    existing.setPermissionName(menu.getName());
                    changed = true;
                }
                if (!"BUTTON".equals(permType)) {
                    String path = menu.getRoutePath();
                    if (!Objects.equals(path, existing.getPath())) {
                        existing.setPath(path);
                        changed = true;
                    }
                }
                Integer sortNo = menu.getSortNo() != null ? menu.getSortNo() : 0;
                if (!sortNo.equals(existing.getSortOrder())) {
                    existing.setSortOrder(sortNo);
                    changed = true;
                }
                if (!parentId.equals(existing.getParentId())) {
                    existing.setParentId(parentId);
                    changed = true;
                }
                if (changed) {
                    permissionMapper.updateById(existing);
                    result.setUpdatedCount(result.getUpdatedCount() + 1);
                    result.getUpdatedNames().add(menu.getName());
                    log.info("同步权限-更新: code={}, name={}", permCode, menu.getName());
                }
            }
        }

        log.info("权限同步完成: 新增={}, 更新={}, 跳过={}",
                result.getCreatedCount(), result.getUpdatedCount(), result.getSkippedCount());
        return result;
    }

    /**
     * 解析菜单的父级 permission ID
     *
     * <p>通过 sys_menu 的 parent_id 找到父菜单的 permission_code，
     * 再通过 permission_code 找到对应的 permission ID。
     * 若父菜单无 permission_code 或找不到对应权限，返回 0（顶级）。
     */
    private Long resolveParentPermissionId(Menu menu,
                                            Map<Long, String> menuIdToCode,
                                            Map<String, Permission> codeToPerm) {
        if (menu.getParentId() == null || menu.getParentId() <= 0) {
            return 0L;
        }
        String parentCode = menuIdToCode.get(menu.getParentId());
        if (parentCode == null) {
            return 0L;
        }
        Permission parentPerm = codeToPerm.get(parentCode);
        if (parentPerm == null) {
            return 0L;
        }
        return parentPerm.getId();
    }

    // ===== Excel 导入导出 =====

    /**
     * 导出角色列表到 Excel
     */
    public void exportRoles(HttpServletResponse response) {
        List<UserRole> roles = listActiveRoles();

        // 收集每个角色的权限编码
        List<List<Object>> rows = new ArrayList<>();
        for (UserRole role : roles) {
            List<String> permissionCodes;
            if (BUILTIN_ROLE_CODE.equalsIgnoreCase(role.getRoleCode())
                    || BUILTIN_SUPER_ROLE_CODE.equalsIgnoreCase(role.getRoleCode())) {
                permissionCodes = Collections.singletonList("*");
            } else {
                permissionCodes = rolePermissionMapper.selectPermissionCodesByRoleId(role.getId());
            }
            List<Object> row = new ArrayList<>();
            row.add(role.getRoleName());
            row.add(role.getRoleCode());
            row.add(role.getDescription() != null ? role.getDescription() : "");
            row.add(role.getSortOrder() != null ? role.getSortOrder() : 0);
            row.add(Integer.valueOf(1).equals(role.getIsActive()) ? "启用" : "停用");
            row.add(String.join(",", permissionCodes));
            rows.add(row);
        }

        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition",
                    "attachment;filename=" + URLEncoder.encode("角色列表.xlsx", "UTF-8"));
            OutputStream out = response.getOutputStream();
            ExcelWriter writer = cn.hutool.poi.excel.ExcelUtil.getWriter(true);
            writer.writeCellValue(0, 0, "角色名称");
            writer.writeCellValue(1, 0, "角色编码");
            writer.writeCellValue(2, 0, "描述");
            writer.writeCellValue(3, 0, "排序号");
            writer.writeCellValue(4, 0, "状态");
            writer.writeCellValue(5, 0, "权限编码");
            for (int i = 0; i < rows.size(); i++) {
                List<Object> row = rows.get(i);
                for (int j = 0; j < row.size(); j++) {
                    writer.writeCellValue(j, i + 1, row.get(j));
                }
            }
            writer.flush(out, true);
            writer.close();
        } catch (IOException e) {
            log.error("导出角色 Excel 失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导出失败: " + e.getMessage());
        }
    }

    /**
     * 从 Excel 导入角色
     */
    @Transactional(rollbackFor = Exception.class)
    public RoleImportResult importRoles(MultipartFile file) {
        RoleImportResult result = new RoleImportResult();
        if (file == null || file.isEmpty()) {
            result.getErrors().add("文件为空");
            return result;
        }

        try {
            ExcelReader reader = cn.hutool.poi.excel.ExcelUtil.getReader(file.getInputStream());
            List<List<Object>> rows = reader.read();
            reader.close();

            // 跳过表头行
            for (int i = 1; i < rows.size(); i++) {
                List<Object> row = rows.get(i);
                if (row.isEmpty()) {
                    continue;
                }
                try {
                    String roleName = row.size() > 0 ? String.valueOf(row.get(0)).trim() : "";
                    String roleCode = row.size() > 1 ? String.valueOf(row.get(1)).trim() : "";
                    String description = row.size() > 2 ? String.valueOf(row.get(2)).trim() : "";
                    int sortOrder = row.size() > 3 ? parseIntSafe(row.get(3)) : 0;

                    if (roleName.isEmpty() || roleCode.isEmpty()) {
                        result.getErrors().add("第 " + (i + 1) + " 行: 角色名称和编码不能为空");
                        result.setFailCount(result.getFailCount() + 1);
                        continue;
                    }

                    // 跳过内置角色（ADMIN / SUPER_ADMIN）
                    if (BUILTIN_ROLE_CODE.equalsIgnoreCase(roleCode)
                            || BUILTIN_SUPER_ROLE_CODE.equalsIgnoreCase(roleCode)) {
                        result.getErrors().add("第 " + (i + 1) + " 行: " + roleCode + " 为系统内置角色，已跳过");
                        result.setFailCount(result.getFailCount() + 1);
                        continue;
                    }

                    // 按 roleCode 查找是否已存在
                    LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(UserRole::getRoleCode, roleCode.toUpperCase());
                    UserRole existing = userRoleMapper.selectOne(wrapper);

                    if (existing != null) {
                        // 更新
                        existing.setRoleName(roleName);
                        existing.setDescription(description);
                        existing.setSortOrder(sortOrder);
                        userRoleMapper.updateById(existing);
                        // 同步字典
                        syncDictOnUpdate(roleCode.toUpperCase(), existing);
                    } else {
                        // 新建
                        UserRole role = new UserRole();
                        role.setRoleName(roleName);
                        role.setRoleCode(roleCode.toUpperCase());
                        role.setDescription(description);
                        role.setSortOrder(sortOrder);
                        role.setIsActive(1);
                        userRoleMapper.insert(role);
                        // 同步字典
                        syncDictOnCreate(role);
                    }
                    result.setSuccessCount(result.getSuccessCount() + 1);
                } catch (Exception e) {
                    result.getErrors().add("第 " + (i + 1) + " 行: " + e.getMessage());
                    result.setFailCount(result.getFailCount() + 1);
                }
            }
        } catch (IOException e) {
            log.error("导入角色 Excel 失败", e);
            throw new BusinessException(ErrorCode.EXCEL_IMPORT_FAILED, "文件读取失败: " + e.getMessage());
        }

        log.info("角色导入完成: 成功={}, 失败={}", result.getSuccessCount(), result.getFailCount());
        return result;
    }

    // ===== 私有方法 =====

    private void checkRoleCodeDuplicate(String roleCode, Long excludeId) {
        if (roleCode == null || roleCode.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRole::getRoleCode, roleCode.toUpperCase());
        if (excludeId != null) {
            wrapper.ne(UserRole::getId, excludeId);
        }
        Long count = userRoleMapper.selectCount(wrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.ROLE_CODE_DUPLICATE, "角色编码已存在: " + roleCode);
        }
    }

    private void checkBuiltinRole(UserRole role) {
        if (BUILTIN_ROLE_CODE.equalsIgnoreCase(role.getRoleCode())
                || BUILTIN_SUPER_ROLE_CODE.equalsIgnoreCase(role.getRoleCode())) {
            // 内置角色保护：仅 superAdmin 账号（SUPER_ADMIN）可操作内置角色
            if (isCurrentUserAdmin()) {
                return;
            }
            throw new BusinessException(ErrorCode.ROLE_IS_BUILTIN, "系统内置角色不可修改或删除");
        }
    }

    /**
     * 判断当前登录用户是否为 superAdmin 账号
     */
    private boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) {
            User currentUser = (User) auth.getPrincipal();
            return RESERVED_USERNAME.equalsIgnoreCase(currentUser.getUsername());
        }
        return false;
    }

    private void bindPermissions(Long roleId, List<PermissionAssignmentDTO> permissions) {
        for (PermissionAssignmentDTO pa : permissions) {
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(pa.getPermissionId());
            rp.setControlMode(pa.getControlMode());
            rolePermissionMapper.insert(rp);
        }
    }

    // ===== 角色-字典同步 =====

    /**
     * 新增角色时同步创建 sys_dict 字典条目
     *
     * <p>映射关系：dict_value = roleCode，dict_value_name = roleName，
     * sort_no = sortOrder，remark = description。
     */
    private void syncDictOnCreate(UserRole role) {
        Dict dict = new Dict();
        dict.setDictType(ROLE_DICT_TYPE);
        dict.setDictTypeName(ROLE_DICT_TYPE_NAME);
        dict.setDictValue(role.getRoleCode());
        dict.setDictValueName(role.getRoleName());
        dict.setSortNo(role.getSortOrder() != null ? role.getSortOrder() : 0);
        dict.setRemark(role.getDescription());
        dict.setIsActive(1);
        dictMapper.insert(dict);
        log.info("同步字典: 角色编码={}, 字典ID={}", role.getRoleCode(), dict.getId());
    }

    /**
     * 更新角色时同步更新 sys_dict 字典条目
     *
     * @param oldRoleCode 更新前的角色编码（用于定位字典条目）
     */
    private void syncDictOnUpdate(String oldRoleCode, UserRole role) {
        LambdaQueryWrapper<Dict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Dict::getDictType, ROLE_DICT_TYPE)
                .eq(Dict::getDictValue, oldRoleCode);
        Dict dict = dictMapper.selectOne(wrapper);
        if (dict != null) {
            dict.setDictValue(role.getRoleCode());
            dict.setDictValueName(role.getRoleName());
            dict.setSortNo(role.getSortOrder());
            dict.setRemark(role.getDescription());
            dictMapper.updateById(dict);
            log.info("同步字典: 角色编码 {} -> {}, 字典ID={}", oldRoleCode, role.getRoleCode(), dict.getId());
        } else {
            // 字典条目不存在（可能已被删除），重新创建
            syncDictOnCreate(role);
        }
    }

    /**
     * 删除角色时同步软删除 sys_dict 字典条目
     */
    private void syncDictOnDelete(UserRole role) {
        LambdaQueryWrapper<Dict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Dict::getDictType, ROLE_DICT_TYPE)
                .eq(Dict::getDictValue, role.getRoleCode());
        dictMapper.delete(wrapper);
        log.info("同步字典: 删除角色编码={} 的字典条目", role.getRoleCode());
    }

    /**
     * 切换角色状态时同步切换 sys_dict 字典条目状态
     *
     * <p>停用时软删除字典条目，启用时恢复或重建字典条目。
     * 由于 {@code @TableLogic} 会在查询时自动过滤 is_active=0 的记录，
     * 启用时若找不到（已软删除）则重新创建。
     */
    private void syncDictOnToggleStatus(UserRole role, Integer isActive) {
        LambdaQueryWrapper<Dict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Dict::getDictType, ROLE_DICT_TYPE)
                .eq(Dict::getDictValue, role.getRoleCode());
        if (Integer.valueOf(0).equals(isActive)) {
            // 停用：软删除字典条目
            dictMapper.delete(wrapper);
            log.info("同步字典: 停用角色编码={} 的字典条目", role.getRoleCode());
        } else {
            // 启用：查找是否已有活跃字典条目
            Dict dict = dictMapper.selectOne(wrapper);
            if (dict == null) {
                // 不存在（可能已被软删除），重新创建
                syncDictOnCreate(role);
            }
        }
    }

    private List<PermissionTreeNode> buildTree(List<Permission> permissions) {
        Map<Long, PermissionTreeNode> nodeMap = new LinkedHashMap<>();
        for (Permission p : permissions) {
            PermissionTreeNode node = new PermissionTreeNode();
            node.setId(p.getId());
            node.setPermissionName(p.getPermissionName());
            node.setPermissionCode(p.getPermissionCode());
            node.setType(p.getType());
            node.setParentId(p.getParentId());
            node.setPath(p.getPath());
            node.setSortOrder(p.getSortOrder());
            node.setDescription(p.getDescription());
            node.setControlMode(p.getControlMode());
            nodeMap.put(p.getId(), node);
        }
        List<PermissionTreeNode> roots = new ArrayList<>();
        for (PermissionTreeNode node : nodeMap.values()) {
            if (node.getParentId() == null || node.getParentId() == 0L) {
                roots.add(node);
            } else {
                PermissionTreeNode parent = nodeMap.get(node.getParentId());
                if (parent != null) {
                    parent.getChildren().add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        return roots;
    }

    private RoleResponse toResponse(UserRole role) {
        RoleResponse response = new RoleResponse();
        response.setId(role.getId());
        response.setRoleName(role.getRoleName());
        response.setRoleCode(role.getRoleCode());
        response.setDescription(role.getDescription());
        response.setSortOrder(role.getSortOrder());
        response.setIsActive(role.getIsActive());
        response.setCreatedAt(role.getCreatedAt());
        response.setUpdatedAt(role.getUpdatedAt());
        return response;
    }

    private int parseIntSafe(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
