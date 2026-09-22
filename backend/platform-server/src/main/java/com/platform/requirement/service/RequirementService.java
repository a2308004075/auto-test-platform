/**
 * @author HXN
 * @date 2026-08-30
 * @description 需求文档管理服务
 */
package com.platform.requirement.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.constant.BizType;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.service.ChangeLogService;
import com.platform.common.service.CommentService;
import com.platform.common.util.ChangeLogHelper;
import com.platform.knowledge.event.ProjectMaterialChangedEvent;
import com.platform.knowledge.service.KnowledgeMaterialCollector;
import com.platform.project.service.ProjectService;
import com.platform.requirement.dto.RequirementItemCreateRequest;
import com.platform.requirement.dto.RequirementItemResponse;
import com.platform.requirement.dto.RequirementVersionCreateRequest;
import com.platform.requirement.dto.RequirementVersionResponse;
import com.platform.requirement.entity.RequirementGroup;
import com.platform.requirement.entity.RequirementItem;
import com.platform.requirement.entity.RequirementVersion;
import com.platform.requirement.mapper.RequirementItemMapper;
import com.platform.requirement.mapper.RequirementVersionMapper;
import com.platform.sys.service.CustomFieldValueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 需求文档管理服务
 *
 * <p>提供需求版本和需求条目的 CRUD 操作。
 * 版本归属于项目，条目归属于版本；版本删除时级联删除其下所有条目（由 FK CASCADE 保证）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RequirementService {

    private final RequirementVersionMapper versionMapper;
    private final RequirementItemMapper itemMapper;
    private final ProjectService projectService;
    private final ChangeLogService changeLogService;
    private final CommentService commentService;
    private final RequirementCaseRelationService requirementCaseRelationService;
    private final RequirementGroupService requirementGroupService;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomFieldValueService customFieldValueService;

    // ===== 版本管理 =====

    /**
     * 查询项目下的版本列表（按创建时间倒序），每个版本附带条目计数
     */
    public List<RequirementVersionResponse> listVersions(Long projectId) {
        LambdaQueryWrapper<RequirementVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementVersion::getProjectId, projectId)
                .orderByDesc(RequirementVersion::getCreatedAt);
        List<RequirementVersion> versions = versionMapper.selectList(wrapper);

        List<RequirementVersionResponse> result = new ArrayList<>();
        for (RequirementVersion v : versions) {
            RequirementVersionResponse resp = toVersionResponse(v);
            // 统计该版本下的条目数量
            LambdaQueryWrapper<RequirementItem> countWrapper = new LambdaQueryWrapper<>();
            countWrapper.eq(RequirementItem::getVersionId, v.getId());
            resp.setItemCount(Math.toIntExact(itemMapper.selectCount(countWrapper)));
            result.add(resp);
        }
        return result;
    }

    /**
     * 创建版本
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementVersionResponse createVersion(RequirementVersionCreateRequest request) {
        projectService.findActiveById(request.getProjectId());

        RequirementVersion version = new RequirementVersion();
        version.setProjectId(request.getProjectId());
        version.setGroupId(resolveGroupId(request.getProjectId(), request.getGroupId()));
        version.setVersionName(request.getVersionName());
        version.setDescription(request.getDescription());
        version.setStatus(request.getStatus() != null ? request.getStatus() : "PLANNING");
        version.setStartDate(request.getStartDate());
        version.setEndDate(request.getEndDate());

        versionMapper.insert(version);

        // 知识库同步：需求版本变更（采集粒度 = 1 版本 = 1 知识库文档）
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                version.getProjectId(), KnowledgeMaterialCollector.SOURCE_REQUIREMENT, version.getId()));

        RequirementVersionResponse resp = toVersionResponse(version);
        resp.setItemCount(0);
        return resp;
    }

    /**
     * 更新版本
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementVersionResponse updateVersion(Long versionId, RequirementVersionCreateRequest request) {
        RequirementVersion version = findVersionById(versionId);

        // 分组：始终解析（null = 归入项目「未分组」系统分组）
        version.setGroupId(resolveGroupId(version.getProjectId(), request.getGroupId()));
        version.setVersionName(request.getVersionName());
        version.setDescription(request.getDescription());
        if (request.getStatus() != null) {
            version.setStatus(request.getStatus());
        }
        version.setStartDate(request.getStartDate());
        version.setEndDate(request.getEndDate());

        versionMapper.updateById(version);

        // 知识库同步：需求版本变更
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                version.getProjectId(), KnowledgeMaterialCollector.SOURCE_REQUIREMENT, versionId));

        RequirementVersionResponse resp = toVersionResponse(version);
        LambdaQueryWrapper<RequirementItem> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(RequirementItem::getVersionId, versionId);
        resp.setItemCount(Math.toIntExact(itemMapper.selectCount(countWrapper)));
        return resp;
    }

    /**
     * 删除版本（FK CASCADE 自动删除其下所有条目；同步清理评论与变更记录）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteVersion(Long versionId) {
        RequirementVersion version = findVersionById(versionId);

        // 清理该版本下所有条目的评论、变更记录、自定义字段值与用例关联
        LambdaQueryWrapper<RequirementItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(RequirementItem::getVersionId, versionId);
        List<RequirementItem> items = itemMapper.selectList(itemWrapper);
        for (RequirementItem item : items) {
            commentService.deleteByBiz(BizType.REQUIREMENT_ITEM, item.getId());
            changeLogService.deleteByBiz(BizType.REQUIREMENT_ITEM, item.getId());
            customFieldValueService.deleteByEntity("requirement", item.getId());
            requirementCaseRelationService.deleteByItem(item.getId());
        }

        versionMapper.deleteById(versionId);

        // 知识库同步：需求版本删除
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                version.getProjectId(), KnowledgeMaterialCollector.SOURCE_REQUIREMENT, versionId));
    }

    // ===== 需求条目管理 =====

    /**
     * 查询单个需求条目详情
     */
    public RequirementItemResponse getItem(Long itemId) {
        RequirementItem item = findItemById(itemId);
        RequirementItemResponse resp = toItemResponse(item);
        // 自定义字段值（由【字段管理】动态配置驱动，按所属版本定位项目）
        RequirementVersion version = findVersionById(item.getVersionId());
        resp.setCustomFields(customFieldValueService.loadValues(version.getProjectId(), "requirement", itemId));
        return resp;
    }

    /**
     * 查询版本下的需求条目列表（按排序号升序，创建时间升序）
     */
    public List<RequirementItemResponse> listItems(Long versionId) {
        findVersionById(versionId);

        LambdaQueryWrapper<RequirementItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementItem::getVersionId, versionId)
                .orderByAsc(RequirementItem::getSortOrder)
                .orderByAsc(RequirementItem::getCreatedAt);
        List<RequirementItem> items = itemMapper.selectList(wrapper);

        List<RequirementItemResponse> result = new ArrayList<>();
        for (RequirementItem item : items) {
            result.add(toItemResponse(item));
        }
        return result;
    }

    /**
     * 创建需求条目
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementItemResponse createItem(RequirementItemCreateRequest request) {
        RequirementVersion version = findVersionById(request.getVersionId());

        RequirementItem item = new RequirementItem();
        item.setVersionId(request.getVersionId());
        item.setTitle(request.getTitle());
        item.setDescription(request.getDescription());
        item.setReqType(request.getReqType() != null ? request.getReqType() : "FEATURE");
        item.setPriority(request.getPriority() != null ? request.getPriority() : "MEDIUM");
        item.setStatus(request.getStatus() != null ? request.getStatus() : "PENDING");
        item.setAssignee(request.getAssignee());
        item.setDeadline(request.getDeadline());

        // 自动计算排序号（当前版本最大 sort_order + 1）
        LambdaQueryWrapper<RequirementItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementItem::getVersionId, request.getVersionId())
                .orderByDesc(RequirementItem::getSortOrder)
                .last("LIMIT 1");
        RequirementItem last = itemMapper.selectOne(wrapper);
        item.setSortOrder(last != null && last.getSortOrder() != null ? last.getSortOrder() + 1 : 0);

        itemMapper.insert(item);

        // 保存自定义字段值（新建/编辑统一使用【字段管理】需求单视图配置）
        customFieldValueService.saveValues(version.getProjectId(), "requirement", "edit", item.getId(), request.getCustomFields());

        // 知识库同步：条目变更归入所属版本重新采集
        publishVersionEvent(item.getVersionId());

        return toItemResponse(item);
    }

    /**
     * 更新需求条目
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementItemResponse updateItem(Long itemId, RequirementItemCreateRequest request) {
        RequirementItem item = findItemById(itemId);

        // 记录变更前值
        String oldTitle = item.getTitle();
        String oldDescription = item.getDescription();
        String oldReqType = item.getReqType();
        String oldPriority = item.getPriority();
        String oldStatus = item.getStatus();
        String oldAssignee = item.getAssignee();
        Object oldDeadline = item.getDeadline();

        item.setTitle(request.getTitle());
        item.setDescription(request.getDescription());
        if (request.getReqType() != null) {
            item.setReqType(request.getReqType());
        }
        if (request.getPriority() != null) {
            item.setPriority(request.getPriority());
        }
        if (request.getStatus() != null) {
            item.setStatus(request.getStatus());
        }
        item.setAssignee(request.getAssignee());
        item.setDeadline(request.getDeadline());

        itemMapper.updateById(item);

        // 记录字段变更
        ChangeLogHelper.collect(BizType.REQUIREMENT_ITEM, itemId, changeLogService)
                .compare("title", oldTitle, item.getTitle())
                .compare("description", oldDescription, item.getDescription())
                .compare("reqType", oldReqType, item.getReqType())
                .compare("priority", oldPriority, item.getPriority())
                .compare("status", oldStatus, item.getStatus())
                .compare("assignee", oldAssignee, item.getAssignee())
                .compare("deadline", oldDeadline, item.getDeadline())
                .save();

        // 保存自定义字段值（由【字段管理】动态配置驱动）
        RequirementVersion version = findVersionById(item.getVersionId());
        customFieldValueService.saveValues(version.getProjectId(), "requirement", "edit", itemId, request.getCustomFields());

        // 知识库同步：条目变更归入所属版本重新采集
        publishVersionEvent(item.getVersionId());

        return toItemResponse(item);
    }

    /**
     * 删除需求条目（同步清理评论、变更记录与用例关联）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(Long itemId) {
        RequirementItem item = findItemById(itemId);
        commentService.deleteByBiz(BizType.REQUIREMENT_ITEM, itemId);
        changeLogService.deleteByBiz(BizType.REQUIREMENT_ITEM, itemId);
        requirementCaseRelationService.deleteByItem(itemId);
        // 自定义字段值级联清理
        customFieldValueService.deleteByEntity("requirement", itemId);
        itemMapper.deleteById(itemId);

        // 知识库同步：条目删除归入所属版本重新采集
        publishVersionEvent(item.getVersionId());
    }

    // ===== 内部方法 =====

    /**
     * 发布需求版本变更事件（条目级操作聚合到所属版本，由采集器按版本整体重采）
     */
    private void publishVersionEvent(Long versionId) {
        RequirementVersion version = versionMapper.selectById(versionId);
        if (version != null) {
            eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                    version.getProjectId(), KnowledgeMaterialCollector.SOURCE_REQUIREMENT, versionId));
        }
    }

    private RequirementVersion findVersionById(Long versionId) {
        RequirementVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "版本不存在");
        }
        return version;
    }

    /**
     * 解析版本归属分组：为空时默认项目「未分组」系统分组；
     * 非空时校验分组存在且属于当前项目（防止跨项目错挂）
     */
    private Long resolveGroupId(Long projectId, Long groupId) {
        Map<Long, RequirementGroup> groupMap = requirementGroupService.getGroupMap(projectId);
        if (groupId == null) {
            for (RequirementGroup group : groupMap.values()) {
                if (Integer.valueOf(1).equals(group.getIsSystem()) && "未分组".equals(group.getName())) {
                    return group.getId();
                }
            }
            throw new BusinessException(ErrorCode.REQUIREMENT_GROUP_NOT_FOUND, "项目「未分组」系统分组缺失：" + projectId);
        }
        RequirementGroup group = groupMap.get(groupId);
        if (group == null) {
            throw new BusinessException(ErrorCode.REQUIREMENT_GROUP_NOT_FOUND, "分组不存在：" + groupId);
        }
        return group.getId();
    }

    private RequirementItem findItemById(Long itemId) {
        RequirementItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "需求条目不存在");
        }
        return item;
    }

    private RequirementVersionResponse toVersionResponse(RequirementVersion v) {
        RequirementVersionResponse resp = new RequirementVersionResponse();
        resp.setId(v.getId());
        resp.setProjectId(v.getProjectId());
        resp.setGroupId(v.getGroupId());
        resp.setVersionName(v.getVersionName());
        resp.setDescription(v.getDescription());
        resp.setStatus(v.getStatus());
        resp.setStartDate(v.getStartDate());
        resp.setEndDate(v.getEndDate());
        resp.setCreatedAt(v.getCreatedAt());
        resp.setUpdatedAt(v.getUpdatedAt());
        return resp;
    }

    private RequirementItemResponse toItemResponse(RequirementItem item) {
        RequirementItemResponse resp = new RequirementItemResponse();
        resp.setId(item.getId());
        resp.setVersionId(item.getVersionId());
        resp.setTitle(item.getTitle());
        resp.setDescription(item.getDescription());
        resp.setReqType(item.getReqType());
        resp.setPriority(item.getPriority());
        resp.setStatus(item.getStatus());
        resp.setAssignee(item.getAssignee());
        resp.setDeadline(item.getDeadline());
        resp.setSortOrder(item.getSortOrder());
        resp.setCreatedAt(item.getCreatedAt());
        resp.setUpdatedAt(item.getUpdatedAt());
        return resp;
    }
}
