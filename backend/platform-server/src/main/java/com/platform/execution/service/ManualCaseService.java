/**
 * @author HXN
 * @date 2026-09-22
 * @description 手动化用例管理服务
 */
package com.platform.execution.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.auth.entity.User;
import com.platform.auth.mapper.UserMapper;
import com.platform.common.constant.BizType;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.response.PageResponse;
import com.platform.common.service.ChangeLogService;
import com.platform.common.service.CommentService;
import com.platform.common.util.ChangeLogHelper;
import com.platform.execution.dto.DefectRelationCreateRequest;
import com.platform.execution.dto.ManualCaseAttachmentCreateRequest;
import com.platform.execution.dto.ManualCaseAttachmentResponse;
import com.platform.execution.dto.ManualCaseCreateRequest;
import com.platform.execution.dto.ManualCaseDefectRelationCreateRequest;
import com.platform.execution.dto.ManualCaseResponse;
import com.platform.execution.dto.ManualCaseUpdateRequest;
import com.platform.execution.entity.DefectRelation;
import com.platform.execution.entity.ManualCase;
import com.platform.execution.entity.ManualCaseAttachment;
import com.platform.execution.entity.TestPlanManualCase;
import com.platform.execution.mapper.DefectRelationMapper;
import com.platform.execution.mapper.ManualCaseAttachmentMapper;
import com.platform.execution.mapper.ManualCaseMapper;
import com.platform.execution.mapper.TestPlanManualCaseMapper;
import com.platform.project.service.ProjectService;
import com.platform.requirement.dto.RequirementCaseRelationCreateRequest;
import com.platform.requirement.service.RequirementCaseRelationService;
import com.platform.sys.entity.CustomField;
import com.platform.sys.mapper.CustomFieldMapper;
import com.platform.sys.service.CustomFieldValueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 手动化用例管理服务
 *
 * <p>用例类型/优先级/环境执行标记等属性字段由【页面配置-手动用例字段】动态驱动，
 * 值存 sys_custom_field_value（统一 edit 视图）；用例状态保留 case_status 列，
 * 由详情页状态下拉切换（选项来源为【页面配置】中 fieldKey=case_status 的配置）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManualCaseService {

    private final ManualCaseMapper manualCaseMapper;
    private final ManualCaseAttachmentMapper manualCaseAttachmentMapper;
    private final ManualCaseGroupService manualCaseGroupService;
    private final ProjectService projectService;
    private final ChangeLogService changeLogService;
    private final CommentService commentService;
    private final RequirementCaseRelationService requirementCaseRelationService;
    private final DefectRelationMapper defectRelationMapper;
    private final DefectService defectService;
    private final CustomFieldValueService customFieldValueService;
    private final CustomFieldMapper customFieldMapper;
    private final TestPlanManualCaseMapper testPlanManualCaseMapper;
    private final UserMapper userMapper;

    /**
     * 分页查询手动化用例
     *
     * @param projectId 项目 ID
     * @param groupId   分组 ID（null=不过滤，0=未分组，正数=指定分组含子孙分组）
     */
    public PageResponse<ManualCaseResponse> listCases(Long projectId, Long groupId, String keyword,
                                                       String caseStatus, String customFilters,
                                                       int page, int pageSize) {
        projectService.findActiveById(projectId);

        LambdaQueryWrapper<ManualCase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ManualCase::getProjectId, projectId);

        // 按分组筛选（0=未分组；正数=含子孙分组）
        if (groupId != null) {
            if (groupId == 0L) {
                wrapper.isNull(ManualCase::getGroupId);
            } else {
                Set<Long> groupIds = manualCaseGroupService.getDescendantGroupIds(groupId);
                wrapper.in(ManualCase::getGroupId, groupIds);
            }
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(ManualCase::getTitle, keyword)
                    .or().like(ManualCase::getContent, keyword));
        }
        if (StringUtils.hasText(caseStatus)) {
            wrapper.eq(ManualCase::getCaseStatus, Integer.parseInt(caseStatus));
        }
        // 动态字段筛选（【页面配置-手动用例字段】视图的列作为筛选项）
        applyCustomFieldFilters(projectId, wrapper, customFilters);
        wrapper.orderByDesc(ManualCase::getCreatedAt);

        Page<ManualCase> result = manualCaseMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<ManualCase> cases = result.getRecords();
        // 批量加载自定义字段值（由【页面配置】动态配置驱动，列表页动态列展示用），避免逐条 N+1 查询
        Map<Long, Map<String, String>> customValues = customFieldValueService.loadValuesBatch(
                projectId, "manual_case", cases.stream().map(ManualCase::getId).collect(Collectors.toList()));
        List<ManualCaseResponse> records = new ArrayList<>(cases.size());
        for (ManualCase c : cases) {
            ManualCaseResponse resp = toResponse(c);
            resp.setCustomFields(customValues.get(c.getId()));
            records.add(resp);
        }
        return PageResponse.of(records, result.getTotal(), page, pageSize);
    }

    /**
     * 动态字段筛选：解析 JSON（fieldKey -> 值），EXISTS 子查询匹配字段值
     *
     * <p>值结构：单值字符串（text 模糊匹配，select/user/environment/number 精确匹配）
     * 或二元数组 [start, end]（datetime 按天范围，字段值 'yyyy-MM-dd HH:mm' 字典序等价时间序）；
     * 同一 fieldKey 在 create/edit 两视图可能存于不同 fieldId，按 field_key 关联任一命中即可
     */
    private void applyCustomFieldFilters(Long projectId, LambdaQueryWrapper<ManualCase> wrapper, String customFiltersJson) {
        if (!StringUtils.hasText(customFiltersJson)) {
            return;
        }
        Map<String, Object> filters;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            filters = mapper.readValue(customFiltersJson,
                    mapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class));
        } catch (Exception e) {
            log.warn("解析动态字段筛选 JSON 失败，忽略: {}", customFiltersJson, e);
            return;
        }
        if (filters.isEmpty()) {
            return;
        }
        // 字段定义（fieldKey -> fieldType）：只处理已配置的 fieldKey，防止任意 key 注入查询
        LambdaQueryWrapper<CustomField> fieldWrapper = new LambdaQueryWrapper<>();
        fieldWrapper.eq(CustomField::getProjectId, projectId)
                .eq(CustomField::getModule, "manual_case")
                .eq(CustomField::getIsActive, 1);
        Map<String, String> typeMap = customFieldMapper.selectList(fieldWrapper).stream()
                .collect(Collectors.toMap(CustomField::getFieldKey, CustomField::getFieldType, (a, b) -> a));

        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String fieldKey = entry.getKey();
            String fieldType = typeMap.get(fieldKey);
            if (fieldType == null) {
                continue;
            }
            Object value = entry.getValue();
            String base = "SELECT 1 FROM sys_custom_field_value v JOIN sys_custom_field f ON v.field_id = f.id"
                    + " WHERE v.module = 'manual_case' AND v.entity_id = manual_case.id"
                    + " AND f.project_id = {0} AND f.field_key = {1}";
            if (value instanceof List && ((List<?>) value).size() == 2) {
                // 日期范围 [start, end]（yyyy-MM-dd，含边界）
                List<?> range = (List<?>) value;
                String start = range.get(0) == null ? "" : String.valueOf(range.get(0));
                String end = range.get(1) == null ? "" : String.valueOf(range.get(1));
                boolean hasStart = StringUtils.hasText(start);
                boolean hasEnd = StringUtils.hasText(end);
                if (!hasStart && !hasEnd) {
                    continue;
                }
                if (hasStart && hasEnd) {
                    wrapper.exists(base + " AND v.field_value >= {2} AND v.field_value <= {3}",
                            projectId, fieldKey, start.trim() + " 00:00", end.trim() + " 23:59");
                } else if (hasStart) {
                    wrapper.exists(base + " AND v.field_value >= {2}",
                            projectId, fieldKey, start.trim() + " 00:00");
                } else {
                    wrapper.exists(base + " AND v.field_value <= {2}",
                            projectId, fieldKey, end.trim() + " 23:59");
                }
            } else if (value != null && StringUtils.hasText(String.valueOf(value))) {
                String v = String.valueOf(value).trim();
                if ("text".equals(fieldType)) {
                    wrapper.exists(base + " AND v.field_value LIKE {2}", projectId, fieldKey, "%" + v + "%");
                } else {
                    wrapper.exists(base + " AND v.field_value = {2}", projectId, fieldKey, v);
                }
            }
        }
    }

    /**
     * 获取用例详情（含自定义字段值与附件）
     */
    public ManualCaseResponse getCase(Long caseId) {
        ManualCase c = findById(caseId);
        ManualCaseResponse resp = toResponse(c);
        resp.setCustomFields(customFieldValueService.loadValues(c.getProjectId(), "manual_case", caseId));
        resp.setAttachments(loadAttachments(caseId));
        return resp;
    }

    /**
     * 创建手动化用例（含初始附件、关联需求条目与关联缺陷）
     */
    @Transactional(rollbackFor = Exception.class)
    public ManualCaseResponse createCase(Long projectId, ManualCaseCreateRequest request) {
        projectService.findActiveById(projectId);

        ManualCase c = new ManualCase();
        BeanUtils.copyProperties(request, c);
        c.setProjectId(projectId);
        // 用例状态固定为"使用"（1），后续由详情页状态下拉切换
        c.setCaseStatus(1);
        c.setCreatedBy(getCurrentUserId());
        manualCaseMapper.insert(c);

        // 保存初始附件
        if (request.getAttachments() != null) {
            for (ManualCaseAttachmentCreateRequest a : request.getAttachments()) {
                addAttachment(projectId, c.getId(), a.getFileName(), a.getFileUrl(), a.getFileSize());
            }
        }

        // 保存初始关联需求条目
        if (request.getRequirementItemIds() != null) {
            for (Long itemId : request.getRequirementItemIds()) {
                RequirementCaseRelationCreateRequest relation = new RequirementCaseRelationCreateRequest();
                relation.setCaseType(RequirementCaseRelationService.CASE_TYPE_MANUAL);
                relation.setCaseId(c.getId());
                requirementCaseRelationService.addRelation(itemId, relation);
            }
        }

        // 保存初始关联缺陷（以 targetType=MANUAL_CASE 写入 defect_relation）
        if (request.getDefectRelations() != null) {
            for (ManualCaseDefectRelationCreateRequest rel : request.getDefectRelations()) {
                DefectRelationCreateRequest createRequest = new DefectRelationCreateRequest();
                createRequest.setRelationType(rel.getRelationType());
                createRequest.setTargetType("MANUAL_CASE");
                createRequest.setTargetId(c.getId());
                createRequest.setTargetTitle(c.getTitle());
                defectService.addRelation(projectId, rel.getDefectId(), createRequest);
            }
        }

        // 保存自定义字段值（新建/详情统一使用【页面配置-手动用例字段】视图配置）
        customFieldValueService.saveValues(projectId, "manual_case", "edit", c.getId(), request.getCustomFields());

        return toResponse(c);
    }

    /**
     * 更新手动化用例（支持部分更新；动态字段值按 key 全量覆盖并记录变更）
     */
    @Transactional(rollbackFor = Exception.class)
    public ManualCaseResponse updateCase(Long caseId, ManualCaseUpdateRequest request) {
        ManualCase c = findById(caseId);

        // 记录变更前值
        String oldTitle = c.getTitle();
        String oldContent = c.getContent();
        Long oldGroupId = c.getGroupId();

        if (StringUtils.hasText(request.getTitle())) {
            c.setTitle(request.getTitle());
        }
        if (request.getContent() != null) {
            c.setContent(request.getContent());
        }
        if (request.getGroupId() != null) {
            c.setGroupId(request.getGroupId());
        }
        manualCaseMapper.updateById(c);

        // 记录基础字段变更
        ChangeLogHelper.Collector collector = ChangeLogHelper.collect(BizType.MANUAL_CASE, caseId, changeLogService)
                .compare("title", oldTitle, c.getTitle())
                .compare("content", oldContent, c.getContent())
                .compare("groupId", oldGroupId, c.getGroupId());

        // 保存自定义字段值（由【页面配置】动态配置驱动），前后对比记录变更
        Map<String, String> oldCustomValues = request.getCustomFields() != null
                ? customFieldValueService.loadValues(c.getProjectId(), "manual_case", caseId)
                : null;
        customFieldValueService.saveValues(c.getProjectId(), "manual_case", "edit", caseId, request.getCustomFields());
        if (oldCustomValues != null) {
            Map<String, String> newCustomValues = customFieldValueService.loadValues(c.getProjectId(), "manual_case", caseId);
            Set<String> keys = new HashSet<>();
            keys.addAll(oldCustomValues.keySet());
            keys.addAll(newCustomValues.keySet());
            for (String key : keys) {
                String oldVal = StringUtils.hasText(oldCustomValues.get(key)) ? oldCustomValues.get(key) : null;
                String newVal = StringUtils.hasText(newCustomValues.get(key)) ? newCustomValues.get(key) : null;
                if (!Objects.equals(oldVal, newVal)) {
                    collector.compare("customField:" + key, oldVal, newVal);
                }
            }
        }
        collector.save();

        return toResponse(c);
    }

    /**
     * 删除手动化用例（同步清理评论、变更记录、需求关联、缺陷关联、附件与自定义字段值）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteCase(Long caseId) {
        findById(caseId);
        deleteChildren(caseId);
        manualCaseMapper.deleteById(caseId);
    }

    /**
     * 启用/废弃手动化用例（targetStatus 为空时按当前值取反；
     * 列表快捷启停不传目标值，详情页状态下拉传入目标值）
     */
    @Transactional(rollbackFor = Exception.class)
    public ManualCaseResponse toggleStatus(Long caseId, Integer targetStatus) {
        ManualCase c = findById(caseId);
        Integer oldCaseStatus = c.getCaseStatus();
        Integer newStatus = targetStatus != null
                ? targetStatus
                : (Integer.valueOf(1).equals(oldCaseStatus) ? 0 : 1);
        if (Objects.equals(oldCaseStatus, newStatus)) {
            return toResponse(c);
        }
        c.setCaseStatus(newStatus);
        manualCaseMapper.updateById(c);

        // 记录状态变更
        ChangeLogHelper.collect(BizType.MANUAL_CASE, caseId, changeLogService)
                .compare("caseStatus", oldCaseStatus, newStatus)
                .save();

        return toResponse(c);
    }

    // ───────────── 附件 ─────────────

    /**
     * 添加附件记录（新建暂存附件随创建一次性提交时同样经此落库）
     */
    @Transactional(rollbackFor = Exception.class)
    public ManualCaseAttachmentResponse addAttachment(Long projectId, Long caseId,
                                                      String fileName, String fileUrl, Long fileSize) {
        findByIdAndProject(projectId, caseId);
        ManualCaseAttachment attachment = new ManualCaseAttachment();
        attachment.setManualCaseId(caseId);
        attachment.setFileName(fileName);
        attachment.setFileUrl(fileUrl);
        attachment.setFileSize(fileSize);
        attachment.setCreatedBy(getCurrentUserId());
        attachment.setCreatedAt(LocalDateTime.now());
        manualCaseAttachmentMapper.insert(attachment);

        ChangeLogHelper.collect(BizType.MANUAL_CASE, caseId, changeLogService)
                .compare("attachment", null, fileName)
                .save();
        return toAttachmentResponse(attachment);
    }

    /**
     * 删除附件
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAttachment(Long projectId, Long caseId, Long attachmentId) {
        findByIdAndProject(projectId, caseId);
        ManualCaseAttachment attachment = manualCaseAttachmentMapper.selectById(attachmentId);
        manualCaseAttachmentMapper.deleteById(attachmentId);
        if (attachment != null) {
            ChangeLogHelper.collect(BizType.MANUAL_CASE, caseId, changeLogService)
                    .compare("attachment", attachment.getFileName(), null)
                    .save();
        }
    }

    /**
     * 清空分组及其子孙分组中的所有手动化用例（同步清理评论、变更记录、关联与附件）
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearByGroup(Long projectId, Long groupId) {
        LambdaQueryWrapper<ManualCase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ManualCase::getProjectId, projectId);
        if (groupId == 0L) {
            wrapper.isNull(ManualCase::getGroupId);
        } else {
            wrapper.in(ManualCase::getGroupId, manualCaseGroupService.getDescendantGroupIds(groupId));
        }
        deleteWithRelations(wrapper);
    }

    /**
     * 清空项目下所有手动化用例（同步清理评论、变更记录、关联与附件）
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearByProject(Long projectId) {
        LambdaQueryWrapper<ManualCase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ManualCase::getProjectId, projectId);
        deleteWithRelations(wrapper);
    }

    private void deleteWithRelations(LambdaQueryWrapper<ManualCase> wrapper) {
        List<ManualCase> cases = manualCaseMapper.selectList(wrapper);
        for (ManualCase c : cases) {
            deleteChildren(c.getId());
        }
        manualCaseMapper.delete(wrapper);
    }

    /**
     * 级联清理单个用例的子数据（评论、变更记录、需求关联、缺陷关联、附件、自定义字段值）
     */
    private void deleteChildren(Long caseId) {
        commentService.deleteByBiz(BizType.MANUAL_CASE, caseId);
        changeLogService.deleteByBiz(BizType.MANUAL_CASE, caseId);
        requirementCaseRelationService.deleteByCase(RequirementCaseRelationService.CASE_TYPE_MANUAL, caseId);
        deleteDefectRelations(caseId);
        deleteAttachments(caseId);
        // 自定义字段值级联清理
        customFieldValueService.deleteByEntity("manual_case", caseId);
        // 计划-用例关联行上的关联级动态字段值级联清理
        //（关联行本身由外键随用例/计划级联删除，sys_custom_field_value 无外键需显式清理）
        LambdaQueryWrapper<TestPlanManualCase> relWrapper = new LambdaQueryWrapper<>();
        relWrapper.eq(TestPlanManualCase::getManualCaseId, caseId)
                .select(TestPlanManualCase::getId);
        List<Long> relationIds = testPlanManualCaseMapper.selectList(relWrapper).stream()
                .map(TestPlanManualCase::getId)
                .collect(Collectors.toList());
        customFieldValueService.deleteByEntities("plan_case", relationIds);
    }

    /**
     * 删除某手动化用例的缺陷关联记录
     */
    private void deleteDefectRelations(Long caseId) {
        LambdaQueryWrapper<DefectRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DefectRelation::getTargetType, "MANUAL_CASE")
                .eq(DefectRelation::getTargetId, caseId);
        defectRelationMapper.delete(wrapper);
    }

    /**
     * 删除某手动化用例的附件记录
     */
    private void deleteAttachments(Long caseId) {
        LambdaQueryWrapper<ManualCaseAttachment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ManualCaseAttachment::getManualCaseId, caseId);
        manualCaseAttachmentMapper.delete(wrapper);
    }

    // ───────────────────── 私有方法 ─────────────────────

    private ManualCase findById(Long caseId) {
        ManualCase c = manualCaseMapper.selectById(caseId);
        if (c == null) {
            throw new BusinessException(ErrorCode.MANUAL_CASE_NOT_FOUND, "手动化用例不存在：" + caseId);
        }
        return c;
    }

    /** 按 ID 查询并校验用例归属指定项目（路径 projectId 与用例实际归属不一致时拒绝访问） */
    private ManualCase findByIdAndProject(Long projectId, Long caseId) {
        ManualCase c = findById(caseId);
        if (!Objects.equals(c.getProjectId(), projectId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "手动化用例不属于当前项目");
        }
        return c;
    }

    private List<ManualCaseAttachmentResponse> loadAttachments(Long caseId) {
        LambdaQueryWrapper<ManualCaseAttachment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ManualCaseAttachment::getManualCaseId, caseId).orderByDesc(ManualCaseAttachment::getCreatedAt);
        return manualCaseAttachmentMapper.selectList(wrapper).stream()
                .map(this::toAttachmentResponse).collect(Collectors.toList());
    }

    private ManualCaseResponse toResponse(ManualCase c) {
        ManualCaseResponse r = new ManualCaseResponse();
        BeanUtils.copyProperties(c, r);
        r.setCreatedByName(getUserName(c.getCreatedBy()));
        return r;
    }

    private ManualCaseAttachmentResponse toAttachmentResponse(ManualCaseAttachment attachment) {
        ManualCaseAttachmentResponse resp = new ManualCaseAttachmentResponse();
        BeanUtils.copyProperties(attachment, resp);
        resp.setCreatedByName(getUserName(attachment.getCreatedBy()));
        return resp;
    }

    private String getUserName(Long userId) {
        if (userId == null) return null;
        User user = userMapper.selectById(userId);
        return user != null ? user.getDisplayName() : null;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) {
            return ((User) auth.getPrincipal()).getId();
        }
        return null;
    }
}
