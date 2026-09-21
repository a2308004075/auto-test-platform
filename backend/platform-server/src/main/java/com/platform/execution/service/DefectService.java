/**
 * @author HXN
 * @date 2026-08-30
 * @description 缺陷管理服务
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
import com.platform.common.service.CommentService;
import com.platform.environment.entity.Environment;
import com.platform.environment.mapper.EnvironmentMapper;
import com.platform.execution.dto.*;
import com.platform.execution.entity.*;
import com.platform.execution.mapper.*;
import com.platform.project.service.ProjectService;
import com.platform.sys.entity.CustomField;
import com.platform.sys.mapper.CustomFieldMapper;
import com.platform.sys.service.CustomFieldService;
import com.platform.sys.service.CustomFieldValueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 缺陷管理服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefectService {

    private final DefectMapper defectMapper;
    private final DefectGroupMapper defectGroupMapper;
    private final DefectGroupService defectGroupService;
    private final DefectRelationMapper defectRelationMapper;
    private final DefectAttachmentMapper defectAttachmentMapper;
    private final DefectHistoryMapper defectHistoryMapper;
    private final ManualCaseMapper manualCaseMapper;
    private final AutoCaseMapper autoCaseMapper;
    private final AutoSuiteMapper autoSuiteMapper;
    private final UserMapper userMapper;
    private final ProjectService projectService;
    private final EnvironmentMapper environmentMapper;
    private final CustomFieldValueService customFieldValueService;
    private final CommentService commentService;
    private final CustomFieldMapper customFieldMapper;

    /** 内置状态集合（回退用）：项目未在【字段管理-编辑缺陷】配置"状态"字段时的合法状态 */
    private static final Set<String> VALID_STATUSES = new HashSet<>(Arrays.asList(
            "NEW", "TO_CONFIRM", "FIXING", "TO_DEPLOY", "PENDING", "COMPLETED", "REOPENED", "DEFERRED", "CLOSED"));
    private static final Set<String> HISTORY_FIELDS = new HashSet<>(Arrays.asList(
            "title", "content", "assigneeId", "dueDate", "foundVersion", "moduleName",
            "severity", "source", "environmentId", "reasonDescription", "responsibleId",
            "fixedVersion", "planTestDate", "status", "groupId"));

    /**
     * 分页查询缺陷
     */
    public PageResponse<DefectResponse> listDefects(Long projectId, Long groupId, String keyword,
                                                     String status, String severity, Long assigneeId,
                                                     String createdAtStart, String createdAtEnd, String customFilters,
                                                     int page, int pageSize) {
        projectService.findActiveById(projectId);

        LambdaQueryWrapper<Defect> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Defect::getProjectId, projectId);

        if (groupId != null) {
            if (groupId == 0L) {
                wrapper.isNull(Defect::getGroupId);
            } else {
                Set<Long> groupIds = defectGroupService.getDescendantGroupIds(groupId);
                wrapper.in(Defect::getGroupId, groupIds);
            }
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Defect::getTitle, keyword)
                    .or().like(Defect::getDefectNo, keyword)
                    .or().like(Defect::getReasonDescription, keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Defect::getStatus, status);
        }
        if (StringUtils.hasText(severity)) {
            wrapper.eq(Defect::getSeverity, severity);
        }
        if (assigneeId != null) {
            wrapper.eq(Defect::getAssigneeId, assigneeId);
        }
        // 创建时间范围（yyyy-MM-dd，含边界：起始日 00:00 ≤ createdAt < 结束日次日 00:00）
        if (StringUtils.hasText(createdAtStart)) {
            LocalDateTime start = parseFilterDate(createdAtStart);
            wrapper.ge(start != null, Defect::getCreatedAt, start);
        }
        if (StringUtils.hasText(createdAtEnd)) {
            LocalDateTime end = parseFilterDate(createdAtEnd);
            wrapper.lt(end != null, Defect::getCreatedAt, end == null ? null : end.plusDays(1));
        }
        // 动态字段筛选（【字段管理-编辑缺陷】视图的列作为筛选项）
        applyCustomFieldFilters(projectId, wrapper, customFilters);
        wrapper.orderByDesc(Defect::getCreatedAt);

        Page<Defect> result = defectMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<Defect> defects = result.getRecords();
        // 批量加载自定义字段值（由【字段管理】动态配置驱动，列表页动态列展示用），避免逐条 N+1 查询
        Map<Long, Map<String, String>> customValues = customFieldValueService.loadValuesBatch(
                projectId, "defect", defects.stream().map(Defect::getId).collect(Collectors.toList()));
        List<DefectResponse> records = new ArrayList<>(defects.size());
        for (Defect d : defects) {
            DefectResponse resp = toListResponse(d);
            resp.setCustomFields(customValues.get(d.getId()));
            records.add(resp);
        }
        return PageResponse.of(records, result.getTotal(), page, pageSize);
    }

    /**
     * 动态字段筛选：解析 JSON（fieldKey -> 值），EXISTS 子查询匹配字段值
     *
     * <p>值结构：单值字符串（text 模糊匹配，select/user/environment/number 精确匹配）
     * 或二元数组 [start, end]（datetime 按天范围，字段值 'yyyy-MM-dd HH:mm' 字典序等价时间序）；
     * 同一 fieldKey 的值在 create/edit 两视图可能存于不同 fieldId，按 field_key 关联任一命中即可
     */
    private void applyCustomFieldFilters(Long projectId, LambdaQueryWrapper<Defect> wrapper, String customFiltersJson) {
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
                .eq(CustomField::getModule, "defect")
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
                    + " WHERE v.module = 'defect' AND v.entity_id = defect.id"
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

    /** 解析筛选日期（yyyy-MM-dd）为当天 00:00；非法格式返回 null（该条件忽略） */
    private LocalDateTime parseFilterDate(String date) {
        try {
            return LocalDate.parse(date.trim()).atStartOfDay();
        } catch (Exception e) {
            log.warn("解析筛选日期失败，忽略该条件: {}", date);
            return null;
        }
    }

    /**
     * 查询指派给当前用户的缺陷（我的任务）
     */
    public List<DefectResponse> listAssignedDefects(Long userId) {
        LambdaQueryWrapper<Defect> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Defect::getAssigneeId, userId)
                // 未完成状态 = 除 已修复(COMPLETED)/无需修复(CLOSED) 外的全部状态
                .notIn(Defect::getStatus, Arrays.asList("COMPLETED", "CLOSED"))
                .orderByDesc(Defect::getCreatedAt);
        List<Defect> list = defectMapper.selectList(wrapper);
        return list.stream().map(this::toListResponse).collect(Collectors.toList());
    }

    /**
     * 获取缺陷详情（含嵌套数据）
     */
    public DefectResponse getDefect(Long projectId, Long defectId) {
        Defect defect = findByIdAndProject(projectId, defectId);
        DefectResponse resp = toListResponse(defect);
        resp.setRelations(loadRelations(defectId));
        resp.setAttachments(loadAttachments(defectId));
        resp.setHistories(loadHistories(defectId));
        // 自定义字段值（由【字段管理】动态配置驱动）
        resp.setCustomFields(customFieldValueService.loadValues(defect.getProjectId(), "defect", defectId));
        return resp;
    }

    /**
     * 创建缺陷
     */
    @Transactional(rollbackFor = Exception.class)
    public DefectResponse createDefect(Long projectId, DefectCreateRequest request) {
        projectService.findActiveById(projectId);

        Defect defect = new Defect();
        BeanUtils.copyProperties(request, defect);
        defect.setProjectId(projectId);
        defect.setDefectNo(generateDefectNo(projectId));
        defect.setStatus("NEW");
        defect.setReopenCount(0);
        defect.setCreatedBy(getCurrentUserId());

        defectMapper.insert(defect);

        // 保存初始关联
        if (request.getRelations() != null) {
            for (DefectRelationCreateRequest r : request.getRelations()) {
                createRelation(defect.getId(), r);
            }
        }

        // 保存初始附件
        if (request.getAttachments() != null) {
            for (DefectAttachmentCreateRequest a : request.getAttachments()) {
                addAttachment(projectId, defect.getId(), a.getFileName(), a.getFileUrl(), a.getFileSize());
            }
        }

        // 保存自定义字段值（新建/详情统一使用【字段管理-编辑缺陷】视图配置）
        customFieldValueService.saveValues(projectId, "defect", "edit", defect.getId(), request.getCustomFields());
        return toListResponse(defect);
    }

    /**
     * 更新缺陷
     */
    @Transactional(rollbackFor = Exception.class)
    public DefectResponse updateDefect(Long projectId, Long defectId, DefectUpdateRequest request) {
        Defect defect = findByIdAndProject(projectId, defectId);
        Map<String, String> oldValues = captureSnapshot(defect);

        applyUpdate(defect, request);
        defect.setUpdatedBy(getCurrentUserId());
        defectMapper.updateById(defect);

        Map<String, String> newValues = captureSnapshot(defect);
        saveHistories(defect.getId(), oldValues, newValues);

        // 保存自定义字段值（由【字段管理】动态配置驱动），前后对比记录变更
        Map<String, String> oldCustomValues = request.getCustomFields() != null
                ? customFieldValueService.loadValues(defect.getProjectId(), "defect", defectId)
                : null;
        customFieldValueService.saveValues(defect.getProjectId(), "defect", "edit", defect.getId(), request.getCustomFields());
        if (oldCustomValues != null) {
            saveCustomFieldHistories(defectId, oldCustomValues,
                    customFieldValueService.loadValues(defect.getProjectId(), "defect", defectId));
        }

        return toListResponse(defect);
    }

    /**
     * 删除缺陷
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDefect(Long projectId, Long defectId) {
        findByIdAndProject(projectId, defectId);
        // 级联删除子数据
        deleteDefectChildren(defectId);
        defectMapper.deleteById(defectId);
    }

    /**
     * 缺陷状态流转
     */
    @Transactional(rollbackFor = Exception.class)
    public DefectResponse transitionStatus(Long projectId, Long defectId, DefectStatusTransitionRequest request) {
        Defect defect = findByIdAndProject(projectId, defectId);
        String targetStatus = request.getTargetStatus();
        // 合法状态优先取【字段管理-编辑缺陷】的"状态"字段配置（按项目），无配置回退内置集合
        if (!loadValidStatuses(defect.getProjectId()).contains(targetStatus)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "无效的状态：" + targetStatus);
        }
        String oldStatus = defect.getStatus();
        // 新建为初始状态：流转出去后不允许再切回（与前端状态下拉选项过滤一致）
        if ("NEW".equals(targetStatus) && !"NEW".equals(oldStatus)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "新建为初始状态，不允许从其他状态流转回新建");
        }
        defect.setStatus(targetStatus);
        if ("REOPENED".equals(targetStatus)) {
            defect.setReopenCount((defect.getReopenCount() == null ? 0 : defect.getReopenCount()) + 1);
        }
        defect.setUpdatedBy(getCurrentUserId());
        defectMapper.updateById(defect);

        saveHistory(defectId, "status", oldStatus, targetStatus);
        if (StringUtils.hasText(request.getRemark())) {
            saveHistory(defectId, "remark", null, request.getRemark());
        }
        return toListResponse(defect);
    }

    /**
     * 项目可用状态集合：读【字段管理-编辑缺陷】"状态"字段（fieldKey=defect_status）的选项 value，
     * 无配置或解析失败时回退内置 VALID_STATUSES
     */
    private Set<String> loadValidStatuses(Long projectId) {
        LambdaQueryWrapper<CustomField> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomField::getProjectId, projectId)
                .eq(CustomField::getModule, "defect")
                .eq(CustomField::getViewType, "edit")
                .eq(CustomField::getFieldKey, CustomFieldService.DEFECT_STATUS_FIELD_KEY)
                .eq(CustomField::getIsActive, 1);
        CustomField field = customFieldMapper.selectOne(wrapper);
        if (field == null || !StringUtils.hasText(field.getOptionsJson())) {
            return VALID_STATUSES;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, String>> rows = mapper.readValue(field.getOptionsJson(),
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            Set<String> values = new HashSet<>();
            for (Map<String, String> row : rows) {
                if (StringUtils.hasText(row.get("value"))) {
                    values.add(row.get("value"));
                }
            }
            return values.isEmpty() ? VALID_STATUSES : values;
        } catch (Exception e) {
            log.warn("解析状态字段选项 JSON 失败，回退内置状态集合: {}", field.getOptionsJson(), e);
            return VALID_STATUSES;
        }
    }

    /**
     * 添加关联
     */
    @Transactional(rollbackFor = Exception.class)
    public DefectRelationResponse addRelation(Long projectId, Long defectId, DefectRelationCreateRequest request) {
        findByIdAndProject(projectId, defectId);
        return toRelationResponse(createRelation(defectId, request));
    }

    /**
     * 删除关联
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRelation(Long projectId, Long defectId, Long relationId) {
        findByIdAndProject(projectId, defectId);
        DefectRelation relation = defectRelationMapper.selectById(relationId);
        defectRelationMapper.deleteById(relationId);
        if (relation != null) {
            saveHistory(defectId, "relation", relation.getTargetTitle(), null);
        }
    }

    /**
     * 添加附件记录
     */
    @Transactional(rollbackFor = Exception.class)
    public DefectAttachmentResponse addAttachment(Long projectId, Long defectId, String fileName, String fileUrl, Long fileSize) {
        findByIdAndProject(projectId, defectId);
        DefectAttachment attachment = new DefectAttachment();
        attachment.setDefectId(defectId);
        attachment.setFileName(fileName);
        attachment.setFileUrl(fileUrl);
        attachment.setFileSize(fileSize);
        attachment.setCreatedBy(getCurrentUserId());
        attachment.setCreatedAt(LocalDateTime.now());
        defectAttachmentMapper.insert(attachment);
        saveHistory(defectId, "attachment", null, fileName);
        return toAttachmentResponse(attachment);
    }

    /**
     * 删除附件
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAttachment(Long projectId, Long defectId, Long attachmentId) {
        findByIdAndProject(projectId, defectId);
        DefectAttachment attachment = defectAttachmentMapper.selectById(attachmentId);
        defectAttachmentMapper.deleteById(attachmentId);
        if (attachment != null) {
            saveHistory(defectId, "attachment", attachment.getFileName(), null);
        }
    }

    /**
     * 清空分组及其子孙分组中的所有缺陷
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearByGroup(Long projectId, Long groupId) {
        LambdaQueryWrapper<Defect> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Defect::getProjectId, projectId);
        if (groupId == 0L) {
            wrapper.isNull(Defect::getGroupId);
        } else {
            wrapper.in(Defect::getGroupId, defectGroupService.getDescendantGroupIds(groupId));
        }
        List<Defect> defects = defectMapper.selectList(wrapper);
        for (Defect d : defects) {
            deleteDefectChildren(d.getId());
            defectMapper.deleteById(d.getId());
        }
    }

    /**
     * 清空项目下所有缺陷
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearByProject(Long projectId) {
        LambdaQueryWrapper<Defect> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Defect::getProjectId, projectId);
        List<Defect> defects = defectMapper.selectList(wrapper);
        for (Defect d : defects) {
            deleteDefectChildren(d.getId());
            defectMapper.deleteById(d.getId());
        }
    }

    // ───────────────────── 私有方法 ─────────────────────

    private Defect findById(Long defectId) {
        Defect defect = defectMapper.selectById(defectId);
        if (defect == null) {
            throw new BusinessException(ErrorCode.DEFECT_NOT_FOUND, "缺陷不存在：" + defectId);
        }
        return defect;
    }

    /** 按 ID 查询并校验缺陷归属指定项目（路径 projectId 与缺陷实际归属不一致时拒绝访问） */
    private Defect findByIdAndProject(Long projectId, Long defectId) {
        Defect defect = findById(defectId);
        if (!Objects.equals(defect.getProjectId(), projectId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "缺陷不属于当前项目");
        }
        return defect;
    }

    private String generateDefectNo(Long projectId) {
        Integer maxSeq = defectMapper.selectMaxSequence(projectId);
        int seq = (maxSeq == null ? 1 : maxSeq + 1);
        return String.format("BUG-%d-%06d", projectId, seq);
    }

    private void applyUpdate(Defect defect, DefectUpdateRequest request) {
        if (request.getGroupId() != null) defect.setGroupId(request.getGroupId());
        if (StringUtils.hasText(request.getTitle())) defect.setTitle(request.getTitle());
        if (request.getContent() != null) defect.setContent(request.getContent());
        if (request.getAssigneeId() != null) defect.setAssigneeId(request.getAssigneeId());
        if (request.getDueDate() != null) defect.setDueDate(request.getDueDate());
        if (request.getFoundVersion() != null) defect.setFoundVersion(request.getFoundVersion());
        if (request.getModuleName() != null) defect.setModuleName(request.getModuleName());
        if (request.getSeverity() != null) defect.setSeverity(request.getSeverity());
        if (request.getSource() != null) defect.setSource(request.getSource());
        if (request.getEnvironmentId() != null) defect.setEnvironmentId(request.getEnvironmentId());
        if (request.getReasonDescription() != null) defect.setReasonDescription(request.getReasonDescription());
        if (request.getResponsibleId() != null) defect.setResponsibleId(request.getResponsibleId());
        if (request.getFixedVersion() != null) defect.setFixedVersion(request.getFixedVersion());
        if (request.getPlanTestDate() != null) defect.setPlanTestDate(request.getPlanTestDate());
    }

    private Map<String, String> captureSnapshot(Defect defect) {
        Map<String, String> map = new HashMap<>();
        map.put("title", defect.getTitle());
        map.put("content", defect.getContent());
        map.put("assigneeId", toString(defect.getAssigneeId()));
        map.put("dueDate", toString(defect.getDueDate()));
        map.put("foundVersion", defect.getFoundVersion());
        map.put("moduleName", defect.getModuleName());
        map.put("severity", defect.getSeverity());
        map.put("source", defect.getSource());
        map.put("environmentId", toString(defect.getEnvironmentId()));
        map.put("reasonDescription", defect.getReasonDescription());
        map.put("responsibleId", toString(defect.getResponsibleId()));
        map.put("fixedVersion", defect.getFixedVersion());
        map.put("planTestDate", toString(defect.getPlanTestDate()));
        map.put("status", defect.getStatus());
        map.put("groupId", toString(defect.getGroupId()));
        return map;
    }

    private String toString(Object value) {
        return value == null ? null : value.toString();
    }

    private void saveHistories(Long defectId, Map<String, String> oldValues, Map<String, String> newValues) {
        Long changedBy = getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        for (String field : HISTORY_FIELDS) {
            String oldVal = oldValues.get(field);
            String newVal = newValues.get(field);
            if (!Objects.equals(oldVal, newVal)) {
                // 内容只记录「有变更」：正文体积大且展示无意义，不落库具体值
                if ("content".equals(field)) {
                    oldVal = null;
                    newVal = null;
                }
                DefectHistory history = new DefectHistory();
                history.setDefectId(defectId);
                history.setFieldName(field);
                history.setOldValue(oldVal);
                history.setNewValue(newVal);
                history.setChangedBy(changedBy);
                history.setCreatedAt(now);
                defectHistoryMapper.insert(history);
            }
        }
    }

    /** 动态字段变更记录：逐字段对比新旧值（空串与 null 视为相同），变化项写入变更记录 */
    private void saveCustomFieldHistories(Long defectId, Map<String, String> oldValues, Map<String, String> newValues) {
        Set<String> keys = new HashSet<>();
        keys.addAll(oldValues.keySet());
        keys.addAll(newValues.keySet());
        for (String key : keys) {
            String oldVal = StringUtils.hasText(oldValues.get(key)) ? oldValues.get(key) : null;
            String newVal = StringUtils.hasText(newValues.get(key)) ? newValues.get(key) : null;
            if (!Objects.equals(oldVal, newVal)) {
                saveHistory(defectId, "customField:" + key, oldVal, newVal);
            }
        }
    }

    private void saveHistory(Long defectId, String fieldName, String oldValue, String newValue) {
        DefectHistory history = new DefectHistory();
        history.setDefectId(defectId);
        history.setFieldName(fieldName);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        history.setChangedBy(getCurrentUserId());
        history.setCreatedAt(LocalDateTime.now());
        defectHistoryMapper.insert(history);
    }

    private DefectRelation createRelation(Long defectId, DefectRelationCreateRequest request) {
        Defect defect = findById(defectId);
        String targetType = request.getTargetType();

        // 用例类目标：校验存在性、同项目，并回填标题快照
        String targetTitle = request.getTargetTitle();
        if ("MANUAL_CASE".equals(targetType) || "AUTO_CASE".equals(targetType)) {
            if ("MANUAL_CASE".equals(targetType)) {
                ManualCase manualCase = manualCaseMapper.selectById(request.getTargetId());
                if (manualCase == null) {
                    throw new BusinessException(ErrorCode.MANUAL_CASE_NOT_FOUND, "手动化用例不存在：" + request.getTargetId());
                }
                if (!Objects.equals(manualCase.getProjectId(), defect.getProjectId())) {
                    throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "用例与缺陷不属于同一项目");
                }
                targetTitle = manualCase.getTitle();
            } else {
                AutoCase autoCase = autoCaseMapper.selectById(request.getTargetId());
                if (autoCase == null) {
                    throw new BusinessException(ErrorCode.AUTO_CASE_NOT_FOUND, "自动化用例不存在：" + request.getTargetId());
                }
                AutoSuite suite = autoSuiteMapper.selectById(autoCase.getAutoSuiteId());
                if (suite == null || !Objects.equals(suite.getProjectId(), defect.getProjectId())) {
                    throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "用例与缺陷不属于同一项目");
                }
                targetTitle = autoCase.getName();
            }

            // 防重复
            LambdaQueryWrapper<DefectRelation> dupWrapper = new LambdaQueryWrapper<>();
            dupWrapper.eq(DefectRelation::getDefectId, defectId)
                    .eq(DefectRelation::getTargetType, targetType)
                    .eq(DefectRelation::getTargetId, request.getTargetId());
            if (defectRelationMapper.selectCount(dupWrapper) > 0) {
                throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, "该用例已关联到当前缺陷");
            }
        }

        DefectRelation relation = new DefectRelation();
        relation.setDefectId(defectId);
        relation.setRelationType(StringUtils.hasText(request.getRelationType()) ? request.getRelationType() : "RELATED");
        relation.setTargetType(targetType);
        relation.setTargetId(request.getTargetId());
        relation.setTargetTitle(targetTitle);
        relation.setCreatedBy(getCurrentUserId());
        relation.setCreatedAt(LocalDateTime.now());
        defectRelationMapper.insert(relation);
        saveHistory(defectId, "relation", null, targetTitle);
        return relation;
    }

    /**
     * 按目标反查关联（用例视角：该用例被哪些缺陷关联）
     */
    public List<DefectRelationResponse> listRelationsByTarget(Long projectId, String targetType, Long targetId) {
        projectService.findActiveById(projectId);

        LambdaQueryWrapper<DefectRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DefectRelation::getTargetType, targetType)
                .eq(DefectRelation::getTargetId, targetId)
                .orderByDesc(DefectRelation::getCreatedAt);
        List<DefectRelationResponse> result = new ArrayList<>();
        for (DefectRelation relation : defectRelationMapper.selectList(wrapper)) {
            DefectRelationResponse resp = toRelationResponse(relation);
            Defect defect = defectMapper.selectById(relation.getDefectId());
            if (defect != null) {
                resp.setDefectNo(defect.getDefectNo());
                resp.setDefectTitle(defect.getTitle());
                resp.setDefectStatus(defect.getStatus());
            }
            result.add(resp);
        }
        return result;
    }

    private void deleteDefectChildren(Long defectId) {
        LambdaQueryWrapper<DefectRelation> w2 = new LambdaQueryWrapper<>();
        w2.eq(DefectRelation::getDefectId, defectId);
        defectRelationMapper.delete(w2);

        LambdaQueryWrapper<DefectAttachment> w3 = new LambdaQueryWrapper<>();
        w3.eq(DefectAttachment::getDefectId, defectId);
        defectAttachmentMapper.delete(w3);

        LambdaQueryWrapper<DefectHistory> w4 = new LambdaQueryWrapper<>();
        w4.eq(DefectHistory::getDefectId, defectId);
        defectHistoryMapper.delete(w4);

        // 自定义字段值级联清理
        customFieldValueService.deleteByEntity("defect", defectId);

        // 通用评论级联清理（评论与变更记录模块：bizType=DEFECT）
        commentService.deleteByBiz(BizType.DEFECT, defectId);
    }

    private List<DefectRelationResponse> loadRelations(Long defectId) {
        LambdaQueryWrapper<DefectRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DefectRelation::getDefectId, defectId).orderByDesc(DefectRelation::getCreatedAt);
        return defectRelationMapper.selectList(wrapper).stream()
                .map(this::toRelationResponse).collect(Collectors.toList());
    }

    private List<DefectAttachmentResponse> loadAttachments(Long defectId) {
        LambdaQueryWrapper<DefectAttachment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DefectAttachment::getDefectId, defectId).orderByDesc(DefectAttachment::getCreatedAt);
        return defectAttachmentMapper.selectList(wrapper).stream()
                .map(this::toAttachmentResponse).collect(Collectors.toList());
    }

    private List<DefectHistoryResponse> loadHistories(Long defectId) {
        LambdaQueryWrapper<DefectHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DefectHistory::getDefectId, defectId).orderByDesc(DefectHistory::getCreatedAt);
        return defectHistoryMapper.selectList(wrapper).stream()
                .map(this::toHistoryResponse).collect(Collectors.toList());
    }

    private DefectResponse toListResponse(Defect defect) {
        DefectResponse resp = new DefectResponse();
        BeanUtils.copyProperties(defect, resp);
        resp.setAssigneeName(getUserName(defect.getAssigneeId()));
        resp.setResponsibleName(getUserName(defect.getResponsibleId()));
        resp.setCreatedByName(getUserName(defect.getCreatedBy()));
        resp.setUpdatedByName(getUserName(defect.getUpdatedBy()));
        resp.setEnvironmentName(getEnvironmentName(defect.getEnvironmentId()));
        resp.setGroupName(getGroupName(defect.getGroupId()));
        return resp;
    }

    private DefectRelationResponse toRelationResponse(DefectRelation relation) {
        DefectRelationResponse resp = new DefectRelationResponse();
        BeanUtils.copyProperties(relation, resp);
        return resp;
    }

    private DefectAttachmentResponse toAttachmentResponse(DefectAttachment attachment) {
        DefectAttachmentResponse resp = new DefectAttachmentResponse();
        BeanUtils.copyProperties(attachment, resp);
        resp.setCreatedByName(getUserName(attachment.getCreatedBy()));
        return resp;
    }

    private DefectHistoryResponse toHistoryResponse(DefectHistory history) {
        DefectHistoryResponse resp = new DefectHistoryResponse();
        BeanUtils.copyProperties(history, resp);
        resp.setChangedByName(getUserName(history.getChangedBy()));
        return resp;
    }

    private String getUserName(Long userId) {
        if (userId == null) return null;
        User user = userMapper.selectById(userId);
        return user != null ? user.getDisplayName() : null;
    }

    private String getEnvironmentName(Long environmentId) {
        if (environmentId == null) return null;
        Environment env = environmentMapper.selectById(environmentId);
        return env != null ? env.getName() : null;
    }

    private String getGroupName(Long groupId) {
        if (groupId == null) return null;
        DefectGroup group = defectGroupMapper.selectById(groupId);
        return group != null ? group.getName() : null;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) {
            return ((User) auth.getPrincipal()).getId();
        }
        return null;
    }
}
