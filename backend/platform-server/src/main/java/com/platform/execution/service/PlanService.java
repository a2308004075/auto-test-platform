/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 测试计划管理服务
 */
package com.platform.execution.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.entity.User;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.response.PageResponse;
import com.platform.execution.dto.AutoSuiteBriefDTO;
import com.platform.execution.dto.ManualCaseBriefDTO;
import com.platform.execution.dto.PlanCaseFieldUpdateRequest;
import com.platform.execution.dto.PlanCreateRequest;
import com.platform.execution.dto.PlanResponse;
import com.platform.execution.dto.PlanUpdateRequest;
import com.platform.execution.entity.*;
import com.platform.execution.mapper.*;
import com.platform.environment.entity.Environment;
import com.platform.environment.mapper.EnvironmentMapper;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 测试计划服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanService {

    /**
     * 计划-用例关联级动态字段模块标识（sys_custom_field.module，entity_id=test_plan_manual_case.id）
     */
    private static final String PLAN_CASE_FIELD_MODULE = "plan_case";

    private final TestPlanMapper testPlanMapper;
    private final PlanGroupMapper planGroupMapper;
    private final ProjectService projectService;
    private final EnvironmentMapper environmentMapper;
    private final TestExecutionMapper testExecutionMapper;
    private final AutoSuiteMapper autoSuiteMapper;
    private final AutoCaseMapper autoCaseMapper;
    private final ManualCaseMapper manualCaseMapper;
    private final TestPlanManualCaseMapper testPlanManualCaseMapper;
    private final CustomFieldValueService customFieldValueService;
    private final ObjectMapper objectMapper;

    /**
     * 分页查询测试计划
     *
     * @param groupId       分组 ID（null=不过滤，0=未分组，其他=指定分组含子分组）
     * @param triggerType   触发方式（null=不过滤）
     * @param environmentId 环境 ID（null=不过滤）
     * @param status        状态 1=启用 0=禁用（null=不过滤）
     * @param updateBegin   更新日期起（yyyy-MM-dd，null=不过滤）
     * @param updateEnd     更新日期止（yyyy-MM-dd，null=不过滤）
     * @param suiteKeyword  关联自动化套件名称关键字（null=不过滤，按项目下自动化套件名称模糊匹配）
     * @param planType      计划类型（null=不过滤，AUTO=自动测试计划，MANUAL=手动测试计划）
     */
    public PageResponse<PlanResponse> listPlans(Long projectId, String keyword,
                                                 Long groupId, String triggerType,
                                                 Long environmentId, Integer status,
                                                 String updateBegin, String updateEnd,
                                                 String suiteKeyword, String planType,
                                                 int page, int pageSize) {
        LambdaQueryWrapper<TestPlan> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestPlan::getProjectId, projectId);

        // 按分组过滤
        if (groupId != null) {
            if (groupId == 0L) {
                // 未分组
                wrapper.isNull(TestPlan::getGroupId);
            } else {
                // 指定分组（含子分组递归）
                List<Long> groupIds = getDescendantGroupIds(groupId);
                wrapper.in(TestPlan::getGroupId, groupIds);
            }
        }

        // 按关联自动化套件名称过滤：先查项目下名称匹配的自动化套件 ID，再匹配 auto_suite_ids JSON 数组
        if (StringUtils.hasText(suiteKeyword)) {
            LambdaQueryWrapper<AutoSuite> suiteWrapper = new LambdaQueryWrapper<>();
            suiteWrapper.eq(AutoSuite::getProjectId, projectId)
                    .like(AutoSuite::getName, suiteKeyword)
                    .select(AutoSuite::getId);
            List<Long> matchedSuiteIds = new ArrayList<>();
            for (AutoSuite s : autoSuiteMapper.selectList(suiteWrapper)) {
                matchedSuiteIds.add(s.getId());
            }
            if (matchedSuiteIds.isEmpty()) {
                return PageResponse.empty((long) page, (long) pageSize);
            }
            wrapper.and(w -> {
                boolean first = true;
                for (Long suiteId : matchedSuiteIds) {
                    if (first) {
                        w.apply("JSON_CONTAINS(auto_suite_ids, {0})", String.valueOf(suiteId));
                        first = false;
                    } else {
                        w.or().apply("JSON_CONTAINS(auto_suite_ids, {0})", String.valueOf(suiteId));
                    }
                }
            });
        }

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(TestPlan::getName, keyword)
                    .or().like(TestPlan::getDescription, keyword));
        }

        // 按触发方式过滤
        if (StringUtils.hasText(triggerType)) {
            wrapper.eq(TestPlan::getTriggerType, triggerType);
        }

        // 按计划类型过滤
        if (StringUtils.hasText(planType)) {
            wrapper.eq(TestPlan::getPlanType, planType);
        }

        // 按环境 ID 过滤
        if (environmentId != null) {
            wrapper.eq(TestPlan::getEnvironmentId, environmentId);
        }

        // 按状态过滤
        if (status != null) {
            wrapper.eq(TestPlan::getIsActive, status);
        }

        // 按更新日期范围过滤
        if (StringUtils.hasText(updateBegin)) {
            try {
                wrapper.ge(TestPlan::getUpdatedAt, LocalDate.parse(updateBegin).atStartOfDay());
            } catch (Exception ignored) { /* 忽略无效日期格式 */ }
        }
        if (StringUtils.hasText(updateEnd)) {
            try {
                wrapper.le(TestPlan::getUpdatedAt, LocalDate.parse(updateEnd).atTime(23, 59, 59));
            } catch (Exception ignored) { /* 忽略无效日期格式 */ }
        }

        wrapper.orderByDesc(TestPlan::getCreatedAt);

        Page<TestPlan> result = testPlanMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<PlanResponse> records = new ArrayList<>(result.getRecords().size());
        for (TestPlan p : result.getRecords()) {
            records.add(toResponse(p));
        }
        return PageResponse.of(records, result.getTotal(), page, pageSize);
    }

    /**
     * 获取计划详情
     */
    public PlanResponse getPlan(Long planId) {
        return toResponse(findById(planId));
    }

    /**
     * 创建测试计划
     */
    @Transactional(rollbackFor = Exception.class)
    public PlanResponse createPlan(PlanCreateRequest request) {
        projectService.findActiveById(request.getProjectId());

        // 名称唯一性检查
        LambdaQueryWrapper<TestPlan> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestPlan::getProjectId, request.getProjectId())
                .eq(TestPlan::getName, request.getName());
        if (testPlanMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND, "计划名称已存在：" + request.getName());
        }

        TestPlan plan = new TestPlan();
        BeanUtils.copyProperties(request, plan);
        plan.setAutoSuiteIds(serializeIdList(request.getAutoSuiteIds()));
        plan.setManualCaseIds(serializeIdList(request.getManualCaseIds()));
        plan.setPlanType(request.getPlanType());
        plan.setTriggerType(request.getTriggerType() != null ? request.getTriggerType() : "MANUAL");
        plan.setIsActive(1);
        plan.setCreatedBy(getCurrentUserId());
        testPlanMapper.insert(plan);
        // 同步维护计划-用例关联表（权威数据源，关联级动态字段值的挂载实体）
        if (request.getManualCaseIds() != null) {
            applyManualCaseRelations(plan, request.getManualCaseIds());
        }
        return toResponse(plan);
    }

    /**
     * 更新测试计划
     */
    @Transactional(rollbackFor = Exception.class)
    public PlanResponse updatePlan(Long planId, PlanUpdateRequest request) {
        TestPlan plan = findById(planId);

        if (StringUtils.hasText(request.getName()) && !request.getName().equals(plan.getName())) {
            LambdaQueryWrapper<TestPlan> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TestPlan::getProjectId, plan.getProjectId())
                    .eq(TestPlan::getName, request.getName())
                    .ne(TestPlan::getId, planId);
            if (testPlanMapper.selectCount(wrapper) > 0) {
                throw new BusinessException(ErrorCode.PLAN_NOT_FOUND, "计划名称已存在：" + request.getName());
            }
            plan.setName(request.getName());
        }
        if (request.getDescription() != null) {
            plan.setDescription(request.getDescription());
        }
        // 分组处理：clearGroup=true 时置空（归入未分组），否则有值则更新
        if (Boolean.TRUE.equals(request.getClearGroup())) {
            plan.setGroupId(null);
        } else if (request.getGroupId() != null) {
            plan.setGroupId(request.getGroupId());
        }
        if (request.getAutoSuiteIds() != null) {
            plan.setAutoSuiteIds(serializeIdList(request.getAutoSuiteIds()));
        }
        if (request.getManualCaseIds() != null) {
            plan.setManualCaseIds(serializeIdList(request.getManualCaseIds()));
        }
        if (request.getEnvironmentId() != null) {
            plan.setEnvironmentId(request.getEnvironmentId());
        }
        if (request.getScheduleCron() != null) {
            plan.setScheduleCron(request.getScheduleCron());
        }
        if (request.getTriggerType() != null) {
            plan.setTriggerType(request.getTriggerType());
        }
        if (request.getIsActive() != null) {
            plan.setIsActive(request.getIsActive());
        }

        testPlanMapper.updateById(plan);
        // 同步维护计划-用例关联表（权威数据源；交集保留原顺序，差集删除并清理字段值，新增追加尾部）
        if (request.getManualCaseIds() != null) {
            applyManualCaseRelations(plan, request.getManualCaseIds());
        }
        return toResponse(plan);
    }

    /**
     * 删除测试计划（关联行由外键级联删除，关联级字段值需显式清理）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePlan(Long planId) {
        findById(planId);
        deletePlanCaseFieldValues(Collections.singletonList(planId));
        testPlanMapper.deleteById(planId);
    }

    /**
     * 清空分组及其子孙分组中的所有计划（执行记录由外键级联删除）
     *
     * @param groupId 分组 ID（0 表示未分组）
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearByGroup(Long projectId, Long groupId) {
        LambdaQueryWrapper<TestPlan> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestPlan::getProjectId, projectId);
        if (groupId == 0L) {
            // 未分组
            wrapper.isNull(TestPlan::getGroupId);
        } else {
            // 指定分组（含子孙分组递归）
            wrapper.in(TestPlan::getGroupId, getDescendantGroupIds(groupId));
        }
        deletePlansWithCaseFieldValues(wrapper);
    }

    /**
     * 清空项目下所有计划
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearByProject(Long projectId) {
        LambdaQueryWrapper<TestPlan> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestPlan::getProjectId, projectId);
        deletePlansWithCaseFieldValues(wrapper);
    }

    /**
     * 按条件删除计划前，先显式清理计划-用例关联行上的动态字段值
     * （关联行本身由外键级联随计划删除，sys_custom_field_value 无外键需显式清理）
     */
    private void deletePlansWithCaseFieldValues(LambdaQueryWrapper<TestPlan> wrapper) {
        wrapper.select(TestPlan::getId);
        List<Long> planIds = testPlanMapper.selectList(wrapper).stream()
                .map(TestPlan::getId)
                .collect(Collectors.toList());
        deletePlanCaseFieldValues(planIds);
        testPlanMapper.delete(wrapper);
    }

    /**
     * 批量清理计划用例关联行上的动态字段值（module=plan_case）
     */
    private void deletePlanCaseFieldValues(List<Long> planIds) {
        if (planIds == null || planIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<TestPlanManualCase> relWrapper = new LambdaQueryWrapper<>();
        relWrapper.in(TestPlanManualCase::getPlanId, planIds)
                .select(TestPlanManualCase::getId);
        List<Long> relationIds = testPlanManualCaseMapper.selectList(relWrapper).stream()
                .map(TestPlanManualCase::getId)
                .collect(Collectors.toList());
        customFieldValueService.deleteByEntities(PLAN_CASE_FIELD_MODULE, relationIds);
    }

    /**
     * 更新计划关联用例的动态字段值（行内即时保存，如台架是否执行/整站是否执行）
     *
     * @param relationId 计划-用例关联行 ID（test_plan_manual_case.id）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateCaseFieldValues(Long planId, Long relationId, PlanCaseFieldUpdateRequest request) {
        TestPlan plan = findById(planId);
        TestPlanManualCase relation = testPlanManualCaseMapper.selectById(relationId);
        if (relation == null || !planId.equals(relation.getPlanId())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "计划用例关联不存在：" + relationId);
        }
        customFieldValueService.saveValues(plan.getProjectId(), PLAN_CASE_FIELD_MODULE, "edit",
                relationId, request.getFieldValues());
    }

    private TestPlan findById(Long planId) {
        TestPlan plan = testPlanMapper.selectById(planId);
        if (plan == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND, "测试计划不存在：" + planId);
        }
        return plan;
    }

    private PlanResponse toResponse(TestPlan plan) {
        PlanResponse resp = new PlanResponse();
        BeanUtils.copyProperties(plan, resp);
        resp.setAutoSuiteIds(parseIdList(plan.getAutoSuiteIds()));
        // manualCaseIds 与明细改从关联表读取（权威数据源，见下方），JSON 列仅作写镜像

        // 获取环境名称
        if (plan.getEnvironmentId() != null) {
            Environment env = environmentMapper.selectById(plan.getEnvironmentId());
            if (env != null) {
                resp.setEnvironmentName(env.getName());
            }
        }

        // 获取自动化套件名称列表与明细
        List<Long> autoSuiteIdList = resp.getAutoSuiteIds();
        List<String> autoSuiteNames = new ArrayList<>();
        List<AutoSuiteBriefDTO> autoSuiteDetails = new ArrayList<>();
        int caseCount = 0;
        for (Long autoSuiteId : autoSuiteIdList) {
            AutoSuite suite = autoSuiteMapper.selectById(autoSuiteId);
            if (suite != null) {
                autoSuiteNames.add(suite.getName());
                // 统计该自动化套件下启用的自动化用例数
                LambdaQueryWrapper<AutoCase> caseWrapper = new LambdaQueryWrapper<>();
                caseWrapper.eq(AutoCase::getAutoSuiteId, autoSuiteId)
                        .eq(AutoCase::getIsActive, 1);
                int suiteCaseCount = Math.toIntExact(autoCaseMapper.selectCount(caseWrapper));
                caseCount += suiteCaseCount;
                AutoSuiteBriefDTO suiteBrief = new AutoSuiteBriefDTO();
                suiteBrief.setId(suite.getId());
                suiteBrief.setName(suite.getName());
                suiteBrief.setCaseCount(suiteCaseCount);
                autoSuiteDetails.add(suiteBrief);
            }
        }
        resp.setAutoSuiteNames(autoSuiteNames);
        resp.setCaseCount(caseCount);
        resp.setAutoSuiteDetails(autoSuiteDetails);

        // 获取手动化用例名称列表与明细（关联表为权威读源，含关联级动态字段值）
        List<TestPlanManualCase> caseRelations = listCaseRelations(plan.getId());
        List<Long> manualCaseIdList = caseRelations.stream()
                .map(TestPlanManualCase::getManualCaseId)
                .collect(Collectors.toList());
        List<Long> relationIds = caseRelations.stream()
                .map(TestPlanManualCase::getId)
                .collect(Collectors.toList());
        Map<Long, Map<String, String>> fieldValuesByRelation =
                customFieldValueService.loadValuesBatch(plan.getProjectId(), PLAN_CASE_FIELD_MODULE, relationIds);
        List<String> manualCaseNames = new ArrayList<>();
        List<ManualCaseBriefDTO> manualCaseDetails = new ArrayList<>();
        for (TestPlanManualCase relation : caseRelations) {
            ManualCase manualCase = manualCaseMapper.selectById(relation.getManualCaseId());
            if (manualCase != null) {
                manualCaseNames.add(manualCase.getTitle());
                ManualCaseBriefDTO caseBrief = new ManualCaseBriefDTO();
                caseBrief.setId(manualCase.getId());
                caseBrief.setTitle(manualCase.getTitle());
                caseBrief.setCaseStatus(manualCase.getCaseStatus());
                caseBrief.setRelationId(relation.getId());
                caseBrief.setFieldValues(fieldValuesByRelation.getOrDefault(
                        relation.getId(), Collections.emptyMap()));
                manualCaseDetails.add(caseBrief);
            }
        }
        resp.setManualCaseIds(manualCaseIdList);
        resp.setManualCaseNames(manualCaseNames);
        resp.setManualCaseCount(manualCaseNames.size());
        resp.setManualCaseDetails(manualCaseDetails);

        // 获取最近一次执行记录（COMPLETED 状态）
        LambdaQueryWrapper<TestExecution> execWrapper = new LambdaQueryWrapper<>();
        execWrapper.eq(TestExecution::getPlanId, plan.getId())
                .eq(TestExecution::getStatus, "COMPLETED")
                .orderByDesc(TestExecution::getCreatedAt)
                .last("LIMIT 1");
        List<TestExecution> executions = testExecutionMapper.selectList(execWrapper);
        if (!executions.isEmpty()) {
            TestExecution lastExec = executions.get(0);
            resp.setLastExecutionTime(lastExec.getCreatedAt());
            // 计算通过率
            int total = lastExec.getTotalCases() != null ? lastExec.getTotalCases() : 0;
            int passed = lastExec.getPassedCases() != null ? lastExec.getPassedCases() : 0;
            if (total > 0) {
                resp.setPassRate(Math.round(passed * 1000.0 / total) / 10.0);
            } else {
                resp.setPassRate(0.0);
            }
        }

        return resp;
    }

    private List<Long> parseIdList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            // 指定 List<Long> 元素类型：无类型反序列化会得到 List<Integer>，
            // 强转 Long 时抛 ClassCastException（PlanExecutor/ExecutionService 同款写法）
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 查询计划的用例关联行（按计划内顺序排序）
     */
    private List<TestPlanManualCase> listCaseRelations(Long planId) {
        LambdaQueryWrapper<TestPlanManualCase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestPlanManualCase::getPlanId, planId)
                .orderByAsc(TestPlanManualCase::getSortNo)
                .orderByAsc(TestPlanManualCase::getId);
        return testPlanManualCaseMapper.selectList(wrapper);
    }

    /**
     * 按提交的用例 ID 列表同步维护计划-用例关联表（权威数据源）
     *
     * <p>diff 语义：交集保留原关联行与顺序（关联级字段值随行保留），
     * 差集删除关联行并清理其字段值，新增用例追加到尾部；
     * test_plan.manual_case_ids JSON 列仅作写镜像，由调用方负责同步
     */
    private void applyManualCaseRelations(TestPlan plan, List<Long> caseIds) {
        if (caseIds == null) {
            return;
        }
        List<TestPlanManualCase> existing = listCaseRelations(plan.getId());
        Map<Long, TestPlanManualCase> existingMap = existing.stream()
                .collect(Collectors.toMap(TestPlanManualCase::getManualCaseId, r -> r, (a, b) -> a));

        // 新列表去重（保持提交顺序）
        List<Long> distinctIds = caseIds.stream().distinct().collect(Collectors.toList());

        // 删除：不在新列表中的关联行 + 其关联级字段值
        List<Long> removedRelationIds = existing.stream()
                .filter(r -> !distinctIds.contains(r.getManualCaseId()))
                .map(TestPlanManualCase::getId)
                .collect(Collectors.toList());
        if (!removedRelationIds.isEmpty()) {
            customFieldValueService.deleteByEntities(PLAN_CASE_FIELD_MODULE, removedRelationIds);
            testPlanManualCaseMapper.deleteBatchIds(removedRelationIds);
        }

        // 追加：新列表中尚无关联行的用例（追尾到现有顺序之后）
        int nextSortNo = existing.stream()
                .mapToInt(r -> r.getSortNo() != null ? r.getSortNo() : 0)
                .max()
                .orElse(0);
        for (Long caseId : distinctIds) {
            if (!existingMap.containsKey(caseId)) {
                nextSortNo++;
                TestPlanManualCase relation = new TestPlanManualCase();
                relation.setPlanId(plan.getId());
                relation.setManualCaseId(caseId);
                relation.setSortNo(nextSortNo);
                testPlanManualCaseMapper.insert(relation);
            }
        }
    }

    private String serializeIdList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "序列化 ID 列表失败");
        }
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) {
            return ((User) auth.getPrincipal()).getId();
        }
        return null;
    }

    /**
     * 获取分组及所有后代分组 ID（通过 PlanGroupMapper 递归查询）
     */
    private List<Long> getDescendantGroupIds(Long groupId) {
        List<Long> ids = new ArrayList<>();
        ids.add(groupId);

        LambdaQueryWrapper<PlanGroup> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlanGroup::getParentId, groupId);
        List<PlanGroup> children = planGroupMapper.selectList(wrapper);
        for (PlanGroup child : children) {
            ids.addAll(getDescendantGroupIds(child.getId()));
        }
        return ids;
    }
}
