/**
 * @author HXN
 * @date 2026-09-15
 * @description 需求分组管理服务
 */
package com.platform.requirement.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.project.service.ProjectService;
import com.platform.requirement.dto.RequirementGroupCreateRequest;
import com.platform.requirement.dto.RequirementGroupResponse;
import com.platform.requirement.dto.RequirementGroupUpdateRequest;
import com.platform.requirement.entity.RequirementGroup;
import com.platform.requirement.entity.RequirementItem;
import com.platform.requirement.entity.RequirementVersion;
import com.platform.requirement.mapper.RequirementGroupMapper;
import com.platform.requirement.mapper.RequirementItemMapper;
import com.platform.requirement.mapper.RequirementVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 需求分组管理服务
 *
 * <p>对齐代码仓库模块 CodeRepositoryGroupService 的行为：
 * 树形分组（parentId）、系统分组保护、itemCount 自底向上聚合、
 * 删除前强制检查子分组与版本（非空禁止删除）。
 */
@Service
@RequiredArgsConstructor
public class RequirementGroupService {

    private final RequirementGroupMapper groupMapper;
    private final RequirementVersionMapper versionMapper;
    private final RequirementItemMapper itemMapper;
    private final ProjectService projectService;

    /**
     * 查询项目下的分组列表（扁平列表，前端自行建树）
     * <p>itemCount 为分组下需求条目总数（含子孙分组，自底向上聚合）。
     */
    public List<RequirementGroupResponse> listByProject(Long projectId) {
        LambdaQueryWrapper<RequirementGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementGroup::getProjectId, projectId);
        wrapper.orderByDesc(RequirementGroup::getIsSystem, RequirementGroup::getCreatedAt);

        List<RequirementGroup> list = groupMapper.selectList(wrapper);

        // 项目下所有版本（仅取 id 与归属分组）
        LambdaQueryWrapper<RequirementVersion> versionWrapper = new LambdaQueryWrapper<>();
        versionWrapper.eq(RequirementVersion::getProjectId, projectId);
        versionWrapper.select(RequirementVersion::getId, RequirementVersion::getGroupId);
        List<RequirementVersion> versions = versionMapper.selectList(versionWrapper);

        // 一次性统计所有版本的需求条目数（key=版本 ID，value=条目数）
        Map<Long, Integer> itemCountByVersion = new HashMap<>();
        if (!versions.isEmpty()) {
            List<Long> versionIds = versions.stream()
                    .map(RequirementVersion::getId)
                    .collect(Collectors.toList());
            QueryWrapper<RequirementItem> itemWrapper = new QueryWrapper<>();
            itemWrapper.select("version_id", "COUNT(*) AS item_count");
            itemWrapper.in("version_id", versionIds);
            itemWrapper.groupBy("version_id");
            for (Map<String, Object> row : itemMapper.selectMaps(itemWrapper)) {
                itemCountByVersion.put(Long.valueOf(row.get("version_id").toString()),
                        Integer.valueOf(row.get("item_count").toString()));
            }
        }

        // 统计每个分组的直接需求条目数（其直接挂靠版本下的条目数）
        Map<Long, Integer> directCountMap = new LinkedHashMap<>();
        for (RequirementGroup group : list) {
            Long groupId = group.getId();
            int directCount = versions.stream()
                    .filter(v -> groupId.equals(v.getGroupId()))
                    .mapToInt(v -> itemCountByVersion.getOrDefault(v.getId(), 0))
                    .sum();
            directCountMap.put(groupId, directCount);
        }

        // 建树后自底向上聚合子分组条目数
        Map<Long, List<RequirementGroup>> childrenMap = list.stream()
                .filter(g -> g.getParentId() != null)
                .collect(Collectors.groupingBy(RequirementGroup::getParentId));

        Map<Long, Integer> totalCountMap = new LinkedHashMap<>();
        for (RequirementGroup group : list) {
            totalCountMap.put(group.getId(), aggregateCount(group.getId(), directCountMap, childrenMap));
        }

        List<RequirementGroupResponse> result = new ArrayList<>();
        for (RequirementGroup group : list) {
            RequirementGroupResponse resp = toResponse(group);
            resp.setItemCount(totalCountMap.getOrDefault(group.getId(), 0));
            result.add(resp);
        }
        return result;
    }

    /**
     * 获取项目的「未分组」系统分组（不存在时自动创建，保证数据完整性）
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementGroup getOrCreateUngrouped(Long projectId) {
        LambdaQueryWrapper<RequirementGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementGroup::getProjectId, projectId)
                .eq(RequirementGroup::getIsSystem, 1)
                .eq(RequirementGroup::getName, "未分组");
        RequirementGroup group = groupMapper.selectOne(wrapper);
        if (group != null) {
            return group;
        }
        group = new RequirementGroup();
        group.setProjectId(projectId);
        group.setParentId(null);
        group.setName("未分组");
        group.setDescription("未分组的需求版本");
        group.setIsSystem(1);
        groupMapper.insert(group);
        return group;
    }

    /**
     * 获取指定分组及其所有子孙分组的 ID 集合
     */
    public Set<Long> getDescendantGroupIds(Long groupId) {
        Set<Long> result = new LinkedHashSet<>();
        result.add(groupId);
        collectDescendants(groupId, result);
        return result;
    }

    /**
     * 查询项目下所有分组，返回以分组 ID 为 key 的 Map
     */
    public Map<Long, RequirementGroup> getGroupMap(Long projectId) {
        LambdaQueryWrapper<RequirementGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementGroup::getProjectId, projectId);
        return groupMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(RequirementGroup::getId, g -> g, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 创建分组
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementGroupResponse create(RequirementGroupCreateRequest request) {
        projectService.findActiveById(request.getProjectId());

        if (request.getParentId() != null) {
            findById(request.getParentId());
        }
        checkNameDuplicate(request.getProjectId(), request.getName(), null);

        RequirementGroup group = new RequirementGroup();
        group.setProjectId(request.getProjectId());
        group.setParentId(request.getParentId());
        group.setName(request.getName());
        group.setDescription(request.getDescription());
        group.setIsSystem(0);

        groupMapper.insert(group);
        return toResponse(group);
    }

    /**
     * 更新分组
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementGroupResponse update(Long groupId, RequirementGroupUpdateRequest request) {
        RequirementGroup group = findById(groupId);

        if (Integer.valueOf(1).equals(group.getIsSystem())) {
            throw new BusinessException(ErrorCode.REQUIREMENT_GROUP_SYSTEM, "系统分组不允许修改");
        }

        if (StringUtils.hasText(request.getName())) {
            checkNameDuplicate(group.getProjectId(), request.getName(), groupId);
            group.setName(request.getName());
        }
        if (request.getDescription() != null) {
            group.setDescription(request.getDescription());
        }
        // parentId：始终应用（null = 移到根级）
        Long newParentId = request.getParentId();
        if (newParentId != null && newParentId.equals(group.getId())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "不能将分组设为自身的子分组");
        }
        if (newParentId != null && getDescendantGroupIds(group.getId()).contains(newParentId)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "不能将分组移动到其子分组下");
        }
        group.setParentId(newParentId);

        groupMapper.updateById(group);
        return toResponse(group);
    }

    /**
     * 删除分组（系统分组不允许删除；存在子分组或版本时禁止删除，需先移动内容）
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long groupId) {
        RequirementGroup group = findById(groupId);

        if (Integer.valueOf(1).equals(group.getIsSystem())) {
            throw new BusinessException(ErrorCode.REQUIREMENT_GROUP_SYSTEM, "系统分组不允许删除");
        }

        // 检查是否有子分组
        LambdaQueryWrapper<RequirementGroup> childWrapper = new LambdaQueryWrapper<>();
        childWrapper.eq(RequirementGroup::getParentId, groupId);
        if (groupMapper.selectCount(childWrapper) > 0) {
            throw new BusinessException(ErrorCode.REQUIREMENT_GROUP_NOT_EMPTY, "分组下存在子分组，请先删除子分组");
        }

        // 检查分组下是否有版本
        LambdaQueryWrapper<RequirementVersion> versionWrapper = new LambdaQueryWrapper<>();
        versionWrapper.eq(RequirementVersion::getGroupId, groupId);
        if (versionMapper.selectCount(versionWrapper) > 0) {
            throw new BusinessException(ErrorCode.REQUIREMENT_GROUP_NOT_EMPTY, "分组下存在版本，请先移动或删除版本");
        }

        groupMapper.deleteById(groupId);
    }

    // ───────────────────── 私有方法 ─────────────────────

    /**
     * 递归聚合分组及其子分组的需求条目数
     */
    private int aggregateCount(Long groupId, Map<Long, Integer> directCountMap,
                               Map<Long, List<RequirementGroup>> childrenMap) {
        int count = directCountMap.getOrDefault(groupId, 0);
        List<RequirementGroup> children = childrenMap.get(groupId);
        if (children != null) {
            for (RequirementGroup child : children) {
                count += aggregateCount(child.getId(), directCountMap, childrenMap);
            }
        }
        return count;
    }

    /**
     * 递归收集子孙分组 ID
     */
    private void collectDescendants(Long parentId, Set<Long> collected) {
        LambdaQueryWrapper<RequirementGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementGroup::getParentId, parentId);
        List<RequirementGroup> children = groupMapper.selectList(wrapper);
        for (RequirementGroup child : children) {
            collected.add(child.getId());
            collectDescendants(child.getId(), collected);
        }
    }

    /**
     * 检查同项目下是否存在同名分组（排除指定 ID）
     */
    private void checkNameDuplicate(Long projectId, String name, Long excludeId) {
        LambdaQueryWrapper<RequirementGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementGroup::getProjectId, projectId);
        wrapper.eq(RequirementGroup::getName, name);
        if (excludeId != null) {
            wrapper.ne(RequirementGroup::getId, excludeId);
        }
        if (groupMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.REQUIREMENT_GROUP_NAME_DUPLICATE,
                    "分组名称已存在：" + name);
        }
    }

    private RequirementGroup findById(Long groupId) {
        RequirementGroup group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new BusinessException(ErrorCode.REQUIREMENT_GROUP_NOT_FOUND, "分组不存在：" + groupId);
        }
        return group;
    }

    private RequirementGroupResponse toResponse(RequirementGroup group) {
        RequirementGroupResponse resp = new RequirementGroupResponse();
        resp.setId(group.getId());
        resp.setProjectId(group.getProjectId());
        resp.setParentId(group.getParentId());
        resp.setName(group.getName());
        resp.setDescription(group.getDescription());
        resp.setIsSystem(group.getIsSystem());
        resp.setCreatedAt(group.getCreatedAt());
        resp.setUpdatedAt(group.getUpdatedAt());
        return resp;
    }
}
