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
import com.platform.sys.service.CustomFieldValueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final DefectWorkLogMapper defectWorkLogMapper;
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

    private static final Set<String> VALID_STATUSES = new HashSet<>(Arrays.asList(
            "NEW", "TO_CONFIRM", "FIXING", "TO_DEPLOY", "PENDING", "COMPLETED", "REOPENED", "DEFERRED", "CLOSED"));
    private static final Set<String> HISTORY_FIELDS = new HashSet<>(Arrays.asList(
            "title", "content", "assigneeId", "dueDate", "foundVersion", "moduleName",
            "severity", "source", "environmentId", "reasonDescription", "responsibleId",
            "fixedVersion", "planTestDate", "status", "groupId", "parentId",
            "estimatedHours", "actualHours", "remainingHours"));

    /**
     * 分页查询缺陷
     */
    public PageResponse<DefectResponse> listDefects(Long projectId, Long groupId, String keyword,
                                                     String status, String severity, Long assigneeId,
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
        wrapper.orderByDesc(Defect::getCreatedAt);

        Page<Defect> result = defectMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<DefectResponse> records = new ArrayList<>(result.getRecords().size());
        for (Defect d : result.getRecords()) {
            records.add(toListResponse(d));
        }
        return PageResponse.of(records, result.getTotal(), page, pageSize);
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
    public DefectResponse getDefect(Long defectId) {
        Defect defect = findById(defectId);
        DefectResponse resp = toDetailResponse(defect);
        resp.setWorkLogs(loadWorkLogs(defectId));
        resp.setRelations(loadRelations(defectId));
        resp.setAttachments(loadAttachments(defectId));
        resp.setHistories(loadHistories(defectId));
        resp.setChildren(loadChildren(defectId));
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
        if (defect.getEstimatedHours() == null) defect.setEstimatedHours(BigDecimal.ZERO);
        if (defect.getActualHours() == null) defect.setActualHours(BigDecimal.ZERO);
        if (defect.getRemainingHours() == null) defect.setRemainingHours(BigDecimal.ZERO);
        defect.setCreatedBy(getCurrentUserId());

        defectMapper.insert(defect);

        // 保存初始关联
        if (request.getRelations() != null) {
            for (DefectRelationCreateRequest r : request.getRelations()) {
                createRelation(defect.getId(), r);
            }
        }

        // 记录创建历史
        saveHistory(defect.getId(), "status", null, "NEW");
        // 保存自定义字段值（由【字段管理】动态配置驱动）
        customFieldValueService.saveValues(projectId, "defect", "create", defect.getId(), request.getCustomFields());
        return toDetailResponse(defect);
    }

    /**
     * 更新缺陷
     */
    @Transactional(rollbackFor = Exception.class)
    public DefectResponse updateDefect(Long defectId, DefectUpdateRequest request) {
        Defect defect = findById(defectId);
        Map<String, String> oldValues = captureSnapshot(defect);

        applyUpdate(defect, request);
        defect.setUpdatedBy(getCurrentUserId());
        defectMapper.updateById(defect);

        Map<String, String> newValues = captureSnapshot(defect);
        saveHistories(defect.getId(), oldValues, newValues);

        // 保存自定义字段值（由【字段管理】动态配置驱动）
        customFieldValueService.saveValues(defect.getProjectId(), "defect", "edit", defect.getId(), request.getCustomFields());

        return toDetailResponse(defect);
    }

    /**
     * 删除缺陷
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDefect(Long defectId) {
        Defect defect = findById(defectId);
        // 级联删除子数据
        deleteDefectChildren(defectId);
        defectMapper.deleteById(defectId);
    }

    /**
     * 缺陷状态流转
     */
    @Transactional(rollbackFor = Exception.class)
    public DefectResponse transitionStatus(Long defectId, DefectStatusTransitionRequest request) {
        Defect defect = findById(defectId);
        String targetStatus = request.getTargetStatus();
        if (!VALID_STATUSES.contains(targetStatus)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "无效的状态：" + targetStatus);
        }
        String oldStatus = defect.getStatus();
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
        return toDetailResponse(defect);
    }

    /**
     * 添加工时记录
     */
    @Transactional(rollbackFor = Exception.class)
    public DefectWorkLogResponse addWorkLog(Long defectId, DefectWorkLogRequest request) {
        findById(defectId);
        DefectWorkLog workLog = new DefectWorkLog();
        workLog.setDefectId(defectId);
        workLog.setUserId(getCurrentUserId());
        workLog.setLogDate(request.getLogDate() != null ? request.getLogDate() : LocalDate.now());
        workLog.setHours(request.getHours());
        workLog.setWorkType(request.getWorkType());
        workLog.setDescription(request.getDescription());
        workLog.setCreatedAt(LocalDateTime.now());
        defectWorkLogMapper.insert(workLog);

        // 同步汇总工时
        recalcWorkHours(defectId);

        return toWorkLogResponse(workLog);
    }

    /**
     * 删除工时记录
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkLog(Long defectId, Long workLogId) {
        findById(defectId);
        defectWorkLogMapper.deleteById(workLogId);
        recalcWorkHours(defectId);
    }

    /**
     * 添加关联
     */
    @Transactional(rollbackFor = Exception.class)
    public DefectRelationResponse addRelation(Long defectId, DefectRelationCreateRequest request) {
        findById(defectId);
        return toRelationResponse(createRelation(defectId, request));
    }

    /**
     * 删除关联
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRelation(Long defectId, Long relationId) {
        findById(defectId);
        defectRelationMapper.deleteById(relationId);
    }

    /**
     * 添加附件记录
     */
    @Transactional(rollbackFor = Exception.class)
    public DefectAttachmentResponse addAttachment(Long defectId, String fileName, String fileUrl, Long fileSize) {
        findById(defectId);
        DefectAttachment attachment = new DefectAttachment();
        attachment.setDefectId(defectId);
        attachment.setFileName(fileName);
        attachment.setFileUrl(fileUrl);
        attachment.setFileSize(fileSize);
        attachment.setCreatedBy(getCurrentUserId());
        attachment.setCreatedAt(LocalDateTime.now());
        defectAttachmentMapper.insert(attachment);
        return toAttachmentResponse(attachment);
    }

    /**
     * 删除附件
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAttachment(Long defectId, Long attachmentId) {
        findById(defectId);
        defectAttachmentMapper.deleteById(attachmentId);
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
        if (request.getParentId() != null) defect.setParentId(request.getParentId());
        if (request.getEstimatedHours() != null) defect.setEstimatedHours(request.getEstimatedHours());
        if (request.getActualHours() != null) defect.setActualHours(request.getActualHours());
        if (request.getRemainingHours() != null) defect.setRemainingHours(request.getRemainingHours());
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
        map.put("parentId", toString(defect.getParentId()));
        map.put("estimatedHours", toString(defect.getEstimatedHours()));
        map.put("actualHours", toString(defect.getActualHours()));
        map.put("remainingHours", toString(defect.getRemainingHours()));
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

    private void recalcWorkHours(Long defectId) {
        LambdaQueryWrapper<DefectWorkLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DefectWorkLog::getDefectId, defectId);
        List<DefectWorkLog> logs = defectWorkLogMapper.selectList(wrapper);
        BigDecimal actual = logs.stream()
                .map(l -> l.getHours() == null ? BigDecimal.ZERO : l.getHours())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Defect defect = findById(defectId);
        defect.setActualHours(actual);
        if (defect.getEstimatedHours() == null) defect.setEstimatedHours(BigDecimal.ZERO);
        if (defect.getRemainingHours() == null) defect.setRemainingHours(BigDecimal.ZERO);
        defectMapper.updateById(defect);
    }

    private void deleteDefectChildren(Long defectId) {
        LambdaQueryWrapper<DefectWorkLog> w1 = new LambdaQueryWrapper<>();
        w1.eq(DefectWorkLog::getDefectId, defectId);
        defectWorkLogMapper.delete(w1);

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

    private List<DefectWorkLogResponse> loadWorkLogs(Long defectId) {
        LambdaQueryWrapper<DefectWorkLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DefectWorkLog::getDefectId, defectId).orderByDesc(DefectWorkLog::getCreatedAt);
        return defectWorkLogMapper.selectList(wrapper).stream()
                .map(this::toWorkLogResponse).collect(Collectors.toList());
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

    private List<DefectResponse> loadChildren(Long defectId) {
        LambdaQueryWrapper<Defect> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Defect::getParentId, defectId).orderByDesc(Defect::getCreatedAt);
        return defectMapper.selectList(wrapper).stream()
                .map(this::toListResponse).collect(Collectors.toList());
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
        if (defect.getParentId() != null) {
            Defect parent = defectMapper.selectById(defect.getParentId());
            resp.setParentDefectNo(parent != null ? parent.getDefectNo() : null);
        }
        return resp;
    }

    private DefectResponse toDetailResponse(Defect defect) {
        DefectResponse resp = toListResponse(defect);
        resp.setChildren(loadChildren(defect.getId()));
        return resp;
    }

    private DefectWorkLogResponse toWorkLogResponse(DefectWorkLog log) {
        DefectWorkLogResponse resp = new DefectWorkLogResponse();
        BeanUtils.copyProperties(log, resp);
        resp.setUserName(getUserName(log.getUserId()));
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
