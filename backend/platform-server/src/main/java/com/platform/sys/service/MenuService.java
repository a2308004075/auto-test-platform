/**
 * @author HXN
 * @date 2026-08-22 13:28
 * @description 菜单管理服务
 */
package com.platform.sys.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.sys.dto.MenuCreateRequest;
import com.platform.sys.dto.MenuListItem;
import com.platform.sys.dto.MenuSortItem;
import com.platform.sys.dto.MenuTreeNode;
import com.platform.sys.entity.Menu;
import com.platform.sys.mapper.MenuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 菜单管理服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MenuService {

    private final MenuMapper menuMapper;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 获取菜单树（仅启用状态的菜单）
     */
    public List<MenuTreeNode> tree() {
        return tree(null);
    }

    /**
     * 获取权限过滤后的菜单树
     *
     * <p>根据用户权限编码列表过滤菜单：
     * <ul>
     *     <li>permissionCodes 为 null 时返回全部启用菜单（向后兼容）</li>
     *     <li>permissionCodes 包含 "*" 时返回全部菜单（ADMIN 通配）</li>
     *     <li>permission_code 为 NULL 的菜单对所有已认证用户可见</li>
     *     <li>目录类型无可见子项时自动隐藏</li>
     * </ul>
     *
     * @param permissionCodes 用户权限编码列表，null 表示不过滤
     */
    public List<MenuTreeNode> tree(List<String> permissionCodes) {
        LambdaQueryWrapper<Menu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Menu::getIsActive, 1)
                .orderByAsc(Menu::getSortNo)
                .orderByAsc(Menu::getId);
        List<Menu> menus = menuMapper.selectList(wrapper);
        List<MenuTreeNode> nodes = menus.stream().map(this::toTreeNode).collect(Collectors.toList());

        if (permissionCodes == null) {
            return buildTree(nodes, 0L);
        }

        // 权限过滤：先构建完整树，再自底向上剪枝空目录
        Set<String> permSet = new HashSet<>(permissionCodes);
        boolean isAdmin = permSet.contains("*");
        List<MenuTreeNode> fullTree = buildTree(nodes, 0L);
        return pruneInvisibleBranches(fullTree, permSet, isAdmin);
    }

    /**
     * 获取所有菜单（扁平列表，含停用，供管理页面使用）
     */
    public List<MenuListItem> listAll() {
        LambdaQueryWrapper<Menu> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(Menu::getSortNo)
                .orderByAsc(Menu::getId);
        List<Menu> menus = menuMapper.selectList(wrapper);
        return menus.stream().map(this::toListItem).collect(Collectors.toList());
    }

    /**
     * 获取单个菜单
     */
    public MenuListItem get(Long id) {
        Menu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND, "菜单不存在");
        }
        return toListItem(menu);
    }

    /**
     * 更新菜单
     */
    @Transactional(rollbackFor = Exception.class)
    public MenuListItem update(Long id, MenuCreateRequest request) {
        Menu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND, "菜单不存在");
        }
        BeanUtils.copyProperties(request, menu);
        menuMapper.updateById(menu);
        return toListItem(menu);
    }

    /**
     * 删除菜单（软删除）
     * 如果菜单有子菜单，则级联删除子菜单
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Menu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND, "菜单不存在");
        }
        // 级联删除子菜单
        List<Long> childIds = findChildIds(id);
        if (!childIds.isEmpty()) {
            menuMapper.deleteBatchIds(childIds);
        }
        menuMapper.deleteById(id);
    }

    /**
     * 切换菜单启用/停用状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(Long id) {
        Menu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND, "菜单不存在");
        }
        menu.setIsActive(menu.getIsActive() == 1 ? 0 : 1);
        menuMapper.updateById(menu);
    }

    /**
     * 批量更新菜单层级与顺序（菜单管理编辑模式拖拽保存）
     *
     * <p>校验规则：
     * <ul>
     *     <li>parentId=0 表示顶级；否则父菜单必须存在</li>
     *     <li>目录/菜单只能挂在顶级或目录下；按钮只能挂在菜单下</li>
     *     <li>禁止循环引用（父级链不得包含自身）</li>
     * </ul>
     *
     * @param items 排序项列表（id + parentId + sortNo）
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateSort(List<MenuSortItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "排序列表不能为空");
        }

        List<Menu> allMenus = menuMapper.selectList(null);
        Map<Long, Menu> menuMap = allMenus.stream()
                .collect(Collectors.toMap(Menu::getId, m -> m));

        // 最终态父级映射：提交项覆盖，其余沿用库中现值（用于循环引用检测）
        Map<Long, Long> parentMap = new HashMap<>();
        for (Menu menu : allMenus) {
            parentMap.put(menu.getId(), menu.getParentId());
        }

        for (MenuSortItem item : items) {
            if (item.getId() == null || item.getParentId() == null || item.getSortNo() == null) {
                throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                        "排序项缺少必填字段（id/parentId/sortNo）");
            }
            Menu menu = menuMap.get(item.getId());
            if (menu == null) {
                throw new BusinessException(ErrorCode.MENU_NOT_FOUND, "菜单不存在: id=" + item.getId());
            }
            if (item.getId().equals(item.getParentId())) {
                throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                        "菜单「" + menu.getName() + "」不能作为自己的上级");
            }
            if (item.getParentId() == 0) {
                if (menu.getMenuType() != null && menu.getMenuType() == 3) {
                    throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                            "按钮「" + menu.getName() + "」不能作为顶级菜单");
                }
            } else {
                Menu parent = menuMap.get(item.getParentId());
                if (parent == null) {
                    throw new BusinessException(ErrorCode.MENU_NOT_FOUND,
                            "菜单「" + menu.getName() + "」的上级菜单不存在: id=" + item.getParentId());
                }
                if (menu.getMenuType() != null && menu.getMenuType() == 3) {
                    if (parent.getMenuType() == null || parent.getMenuType() != 2) {
                        throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                                "按钮「" + menu.getName() + "」只能挂在菜单下");
                    }
                } else {
                    if (parent.getMenuType() == null || parent.getMenuType() != 1) {
                        throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                                "目录/菜单「" + menu.getName() + "」只能挂在顶级或目录下");
                    }
                }
            }
            parentMap.put(item.getId(), item.getParentId());
        }

        // 循环引用检测：沿父级链上溯，节点重复出现即存在环
        for (MenuSortItem item : items) {
            Set<Long> visited = new HashSet<>();
            Long cur = item.getId();
            while (cur != null && cur != 0) {
                if (!visited.add(cur)) {
                    throw new BusinessException(ErrorCode.RESOURCE_CONFLICT,
                            "菜单层级存在循环引用: id=" + item.getId());
                }
                cur = parentMap.get(cur);
            }
        }

        // 仅更新发生变化的记录
        for (MenuSortItem item : items) {
            Menu menu = menuMap.get(item.getId());
            if (item.getParentId().equals(menu.getParentId())
                    && item.getSortNo().equals(menu.getSortNo())) {
                continue;
            }
            menu.setParentId(item.getParentId());
            menu.setSortNo(item.getSortNo());
            menuMapper.updateById(menu);
        }
    }

    // ===== 私有方法 =====

    private List<Long> findChildIds(Long parentId) {
        LambdaQueryWrapper<Menu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Menu::getParentId, parentId).select(Menu::getId);
        List<Menu> children = menuMapper.selectList(wrapper);
        List<Long> ids = new ArrayList<>();
        for (Menu child : children) {
            ids.add(child.getId());
            ids.addAll(findChildIds(child.getId()));
        }
        return ids;
    }

    private List<MenuTreeNode> buildTree(List<MenuTreeNode> nodes, Long parentId) {
        Map<Long, List<MenuTreeNode>> grouped = nodes.stream()
                .collect(Collectors.groupingBy(MenuTreeNode::getParentId));
        return buildChildren(grouped, parentId);
    }

    private List<MenuTreeNode> buildChildren(Map<Long, List<MenuTreeNode>> grouped, Long parentId) {
        List<MenuTreeNode> children = grouped.getOrDefault(parentId, new ArrayList<>());
        // 显式按 sortNo → id 升序排序，确保菜单显示顺序与数据库 sort_no 一致
        children.sort(Comparator.comparingInt((MenuTreeNode n) -> n.getSortNo() == null ? 0 : n.getSortNo())
                .thenComparingLong(MenuTreeNode::getId));
        for (MenuTreeNode child : children) {
            child.setChildren(buildChildren(grouped, child.getId()));
        }
        return children;
    }

    /**
     * 自底向上剪枝：移除无权限且无可见子项的目录节点
     *
     * @return true 表示该节点可见（应保留），false 表示应剪枝
     */
    private List<MenuTreeNode> pruneInvisibleBranches(List<MenuTreeNode> nodes,
                                                       Set<String> permSet,
                                                       boolean isAdmin) {
        List<MenuTreeNode> result = new ArrayList<>();
        for (MenuTreeNode node : nodes) {
            // 递归处理子节点
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                node.setChildren(pruneInvisibleBranches(node.getChildren(), permSet, isAdmin));
            }
            // 判断当前节点是否可见
            boolean visible = isNodeVisible(node, permSet, isAdmin);
            // 目录类型：自身可见 + 至少有一个可见子项才保留
            if (node.getMenuType() != null && node.getMenuType() == 1) {
                if (visible && node.getChildren() != null && !node.getChildren().isEmpty()) {
                    result.add(node);
                }
            } else if (visible) {
                result.add(node);
            }
        }
        return result;
    }

    /**
     * 判断单个菜单节点是否对当前用户可见
     */
    private boolean isNodeVisible(MenuTreeNode node, Set<String> permSet, boolean isAdmin) {
        if (isAdmin) {
            return true;
        }
        String permCode = node.getPermissionCode();
        // 未关联权限编码的菜单对所有已认证用户可见
        if (permCode == null || permCode.isEmpty()) {
            return true;
        }
        return permSet.contains(permCode);
    }

    private MenuTreeNode toTreeNode(Menu menu) {
        MenuTreeNode node = new MenuTreeNode();
        node.setId(menu.getId());
        node.setParentId(menu.getParentId());
        node.setName(menu.getName());
        node.setMenuType(menu.getMenuType());
        node.setIcon(menu.getIcon());
        node.setRoutePath(menu.getRoutePath());
        node.setComponent(menu.getComponent());
        node.setSortNo(menu.getSortNo());
        node.setIsActive(menu.getIsActive());
        node.setPermissionCode(menu.getPermissionCode());
        return node;
    }

    private MenuListItem toListItem(Menu menu) {
        MenuListItem item = new MenuListItem();
        item.setId(menu.getId());
        item.setParentId(menu.getParentId());
        item.setName(menu.getName());
        item.setMenuType(menu.getMenuType());
        item.setIcon(menu.getIcon());
        item.setRoutePath(menu.getRoutePath());
        item.setComponent(menu.getComponent());
        item.setSortNo(menu.getSortNo());
        item.setIsActive(menu.getIsActive());
        item.setPermissionCode(menu.getPermissionCode());
        if (menu.getCreatedAt() != null) {
            item.setCreatedAt(menu.getCreatedAt().format(DT_FMT));
        }
        if (menu.getUpdatedAt() != null) {
            item.setUpdatedAt(menu.getUpdatedAt().format(DT_FMT));
        }
        return item;
    }
}
