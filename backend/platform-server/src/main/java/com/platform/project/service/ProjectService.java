/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description 项目管理服务
 */
package com.platform.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.action.entity.Action;
import com.platform.action.entity.ActionGroup;
import com.platform.action.mapper.ActionGroupMapper;
import com.platform.action.mapper.ActionMapper;
import com.platform.apidoc.entity.Api;
import com.platform.apidoc.mapper.ApiMapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.response.PageResponse;
import com.platform.execution.entity.AutoCase;
import com.platform.execution.entity.AutoSuite;
import com.platform.execution.entity.TestExecution;
import com.platform.execution.entity.TestPlan;
import com.platform.execution.entity.TestResult;
import com.platform.execution.mapper.AutoCaseMapper;
import com.platform.execution.mapper.AutoSuiteMapper;
import com.platform.execution.mapper.TestExecutionMapper;
import com.platform.execution.mapper.TestPlanMapper;
import com.platform.execution.mapper.TestResultMapper;
import com.platform.keyword.entity.ApiKeyword;
import com.platform.keyword.entity.ApiKeywordGroup;
import com.platform.keyword.entity.Keyword;
import com.platform.keyword.mapper.ApiKeywordGroupMapper;
import com.platform.keyword.mapper.ApiKeywordMapper;
import com.platform.keyword.mapper.KeywordMapper;
import com.platform.project.dto.*;
import com.platform.project.entity.ApiModule;
import com.platform.project.entity.Project;
import com.platform.project.mapper.ApiModuleMapper;
import com.platform.project.mapper.ProjectMapper;
import com.platform.projectdoc.entity.ProjectDocGroup;
import com.platform.projectdoc.mapper.ProjectDocGroupMapper;
import com.platform.repository.entity.CodeRepositoryGroup;
import com.platform.repository.mapper.CodeRepositoryGroupMapper;
import com.platform.requirement.entity.RequirementGroup;
import com.platform.requirement.mapper.RequirementGroupMapper;
import com.platform.sys.service.CustomFieldService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 项目管理服务
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final ApiModuleMapper apiModuleMapper;
    private final ApiMapper apiMapper;
    private final KeywordMapper keywordMapper;
    private final ActionMapper actionMapper;
    private final ActionGroupMapper actionGroupMapper;
    private final ApiKeywordGroupMapper apiKeywordGroupMapper;
    private final AutoSuiteMapper autoSuiteMapper;
    private final AutoCaseMapper autoCaseMapper;
    private final TestPlanMapper testPlanMapper;
    private final TestExecutionMapper testExecutionMapper;
    private final TestResultMapper testResultMapper;
    private final ApiKeywordMapper apiKeywordMapper;
    private final ProjectDocGroupMapper projectDocGroupMapper;
    private final CodeRepositoryGroupMapper codeRepositoryGroupMapper;
    private final RequirementGroupMapper requirementGroupMapper;
    private final CustomFieldService customFieldService;

    /**
     * 分页查询项目列表（含卡片统计）
     */
    public PageResponse<ProjectResponse> listProjects(String keyword, Integer status, int page, int pageSize) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Project::getName, keyword)
                    .or().like(Project::getDescription, keyword));
        }
        if (status != null) {
            wrapper.eq(Project::getStatus, status);
        }
        // 排序：启用优先于停用，同状态下创建时间降序
        wrapper.orderByDesc(Project::getStatus);
        wrapper.orderByDesc(Project::getCreatedAt);

        Page<Project> pageParam = new Page<>(page, pageSize);
        Page<Project> result = projectMapper.selectPage(pageParam, wrapper);

        List<Project> records = result.getRecords();
        List<ProjectResponse> responses = new ArrayList<>();

        List<Long> projectIds = records.stream().map(Project::getId).collect(Collectors.toList());
        // 各类型计数：每类一次查询 + 内存分组，消除 N+1（原为每项目 5 次 selectCount）
        Map<Long, Long> apiCountMap = countByKey(projectIds, apiMapper, Api::getProjectId);
        Map<Long, Long> kwCountMap = countByKey(projectIds, keywordMapper, Keyword::getProjectId);
        Map<Long, Long> actionCountMap = countByKey(projectIds, actionMapper, Action::getProjectId);
        Map<Long, Long> suiteCountMap = countByKey(projectIds, autoSuiteMapper, AutoSuite::getProjectId);
        Map<Long, Long> planCountMap = countByKey(projectIds, testPlanMapper, TestPlan::getProjectId);

        // 自动化用例数：一次查所有项目的自动化套件，再一次查 case 按套件分组
        Map<Long, List<Long>> suiteIdsByProject = new HashMap<>();
        List<Long> allSuiteIds = new ArrayList<>();
        if (!projectIds.isEmpty()) {
            List<AutoSuite> allSuites = autoSuiteMapper.selectList(
                    new LambdaQueryWrapper<AutoSuite>()
                            .select(AutoSuite::getId, AutoSuite::getProjectId)
                            .in(AutoSuite::getProjectId, projectIds));
            for (AutoSuite s : allSuites) {
                if (s.getProjectId() != null && s.getId() != null) {
                    suiteIdsByProject.computeIfAbsent(s.getProjectId(), k -> new ArrayList<>()).add(s.getId());
                    allSuiteIds.add(s.getId());
                }
            }
        }
        Map<Long, Long> caseCountBySuite = countByKey(allSuiteIds, autoCaseMapper, AutoCase::getAutoSuiteId);

        for (Project p : records) {
            ProjectResponse resp = toResponse(p);
            resp.setApiCount(apiCountMap.getOrDefault(p.getId(), 0L));
            resp.setKeywordCount(kwCountMap.getOrDefault(p.getId(), 0L));
            resp.setActionCount(actionCountMap.getOrDefault(p.getId(), 0L));
            resp.setSuiteCount(suiteCountMap.getOrDefault(p.getId(), 0L));
            resp.setPlanCount(planCountMap.getOrDefault(p.getId(), 0L));

            List<Long> sids = suiteIdsByProject.getOrDefault(p.getId(), Collections.emptyList());
            resp.setCaseCount(sids.stream().mapToLong(sid -> caseCountBySuite.getOrDefault(sid, 0L)).sum());
            responses.add(resp);
        }

        return PageResponse.of(responses, result.getTotal(), page, pageSize);
    }

    /**
     * 创建项目（含自动创建系统分组）
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectResponse createProject(ProjectCreateRequest request) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getName, request.getName());
        if (projectMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.PROJECT_NAME_DUPLICATE, "项目名称已存在：" + request.getName());
        }

        Project project = new Project();
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setSourcePath(request.getSourcePath());
        project.setStatus(1);
        project.setDeleted(0);
        projectMapper.insert(project);

        createSystemModule(project.getId(), "全部", null, "系统默认分组，包含所有接口");
        createSystemModule(project.getId(), "未分组", null, "未分组的接口");

        createSystemActionGroup(project.getId(), "全部", null, "系统默认分组，包含所有 Action");
        createSystemActionGroup(project.getId(), "未分组", null, "未分组的 Action");

        createSystemKeywordGroup(project.getId(), "全部", null, "系统默认分组，包含所有接口关键字");
        createSystemKeywordGroup(project.getId(), "未分组", null, "未分组的接口关键字");

        createSystemDocGroup(project.getId(), "全部", null, "系统默认分组，包含所有项目文档");
        createSystemDocGroup(project.getId(), "未分组", null, "未分组的项目文档");

        createSystemRepositoryGroup(project.getId(), "全部", null, "系统默认分组，包含所有仓库");
        createSystemRepositoryGroup(project.getId(), "未分组", null, "未分组的仓库");

        createSystemRequirementGroup(project.getId(), "未分组", null, "未分组的需求版本");

        // 预置缺陷"状态"字段（【页面配置-缺陷字段】视图，流转状态下拉框的选项来源）
        customFieldService.createDefaultStatusField(project.getId());

        // 预置手动用例"状态"字段（【页面配置-手动用例字段】视图，详情页状态下拉框的选项来源）
        customFieldService.createDefaultManualCaseStatusField(project.getId());

        return toResponse(project);
    }

    /**
     * 获取项目详情
     */
    public ProjectResponse getProject(Long projectId) {
        return toResponse(findActiveById(projectId));
    }

    /**
     * 更新项目
     */
    public ProjectResponse updateProject(Long projectId, ProjectUpdateRequest request) {
        Project project = findActiveById(projectId);

        if (StringUtils.hasText(request.getName()) && !request.getName().equals(project.getName())) {
            LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Project::getName, request.getName());
            wrapper.ne(Project::getId, projectId);
            if (projectMapper.selectCount(wrapper) > 0) {
                throw new BusinessException(ErrorCode.PROJECT_NAME_DUPLICATE, "项目名称已存在：" + request.getName());
            }
            project.setName(request.getName());
        }
        if (request.getDescription() != null) {
            project.setDescription(request.getDescription());
        }
        if (request.getSourcePath() != null) {
            project.setSourcePath(request.getSourcePath());
        }

        projectMapper.updateById(project);
        return toResponse(project);
    }

    /**
     * 删除项目（软删除，通过 deleted 字段）
     */
    public void deleteProject(Long projectId) {
        findActiveById(projectId);
        projectMapper.deleteById(projectId);
    }

    /**
     * 启停项目（切换 status 字段，与软删除无关）
     */
    public ProjectResponse toggleStatus(Long projectId) {
        Project project = findActiveById(projectId);
        project.setStatus(project.getStatus() == 1 ? 0 : 1);
        projectMapper.updateById(project);
        return toResponse(project);
    }

    /**
     * 获取项目仪表板
     */
    public ProjectDashboardResponse getDashboard(Long projectId) {
        Project project = findActiveById(projectId);

        DashboardStats stats = new DashboardStats();
        DashboardTrendResponse trend = new DashboardTrendResponse();

        // ── 基本计数 ──
        List<Long> suiteIds = getAutoSuiteIds(projectId);
        List<Long> planIds = getPlanIds(projectId);

        LambdaQueryWrapper<Api> apiWrapper = new LambdaQueryWrapper<>();
        apiWrapper.eq(Api::getProjectId, projectId);
        long totalApiCount = apiMapper.selectCount(apiWrapper);
        stats.setApiCount(totalApiCount);

        LambdaQueryWrapper<Keyword> kwWrapper = new LambdaQueryWrapper<>();
        kwWrapper.eq(Keyword::getProjectId, projectId);
        stats.setKeywordCount(keywordMapper.selectCount(kwWrapper));

        LambdaQueryWrapper<Action> actionWrapper = new LambdaQueryWrapper<>();
        actionWrapper.eq(Action::getProjectId, projectId);
        stats.setActionCount(actionMapper.selectCount(actionWrapper));

        stats.setSuiteCount((long) suiteIds.size());

        if (!suiteIds.isEmpty()) {
            LambdaQueryWrapper<AutoCase> caseWrapper = new LambdaQueryWrapper<>();
            caseWrapper.in(AutoCase::getAutoSuiteId, suiteIds);
            stats.setCaseCount(autoCaseMapper.selectCount(caseWrapper));
        }

        stats.setPlanCount((long) planIds.size());

        // ── 执行统计 ──
        List<TestExecution> allExecutions = new ArrayList<>();
        if (!planIds.isEmpty()) {
            LambdaQueryWrapper<TestExecution> execWrapper = new LambdaQueryWrapper<>();
            execWrapper.in(TestExecution::getPlanId, planIds);
            allExecutions = testExecutionMapper.selectList(execWrapper);
        }
        // 一次性批量加载 plan，避免后续循环内 selectById 造成 N+1
        Set<Long> execPlanIds = allExecutions.stream()
                .map(TestExecution::getPlanId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, TestPlan> planMap = execPlanIds.isEmpty() ? Collections.emptyMap()
                : testPlanMapper.selectBatchIds(execPlanIds).stream()
                        .collect(Collectors.toMap(TestPlan::getId, p -> p));

        stats.setExecutionCount((long) allExecutions.size());
        long passed = 0, failed = 0;
        for (TestExecution e : allExecutions) {
            if (e.getPassedCases() != null) passed += e.getPassedCases();
            if (e.getFailedCases() != null) failed += e.getFailedCases();
        }
        stats.setPassedCases(passed);
        stats.setFailedCases(failed);
        long total = passed + failed;
        double passRate = total > 0 ? Math.round(passed * 1000.0 / total) / 10.0 : 0.0;
        stats.setPassRate(passRate);

        // ── 本周执行次数 ──
        LocalDateTime weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        long weeklyCount = allExecutions.stream()
                .filter(e -> e.getCreatedAt() != null && e.getCreatedAt().isAfter(weekStart))
                .count();
        stats.setWeeklyExecutionCount(weeklyCount);

        // ── 接口覆盖率：已覆盖接口 = 有 api_keyword 绑定的接口数（按 apiId 去重） ──
        if (totalApiCount > 0) {
            LambdaQueryWrapper<ApiKeyword> coveredWrapper = new LambdaQueryWrapper<>();
            coveredWrapper.select(ApiKeyword::getApiId).eq(ApiKeyword::getProjectId, projectId);
            long coveredCount = apiKeywordMapper.selectList(coveredWrapper).stream()
                    .map(ApiKeyword::getApiId).filter(Objects::nonNull).distinct().count();
            coveredCount = Math.min(coveredCount, totalApiCount);
            stats.setCoveredApiCount(coveredCount);
            stats.setApiCoverageRate(Math.round(coveredCount * 1000.0 / totalApiCount) / 10.0);
        }

        // ── 自动化套件完成率 ──
        if (!suiteIds.isEmpty()) {
            Set<Long> executedSuiteIds = new HashSet<>();
            for (TestExecution exec : allExecutions) {
                TestPlan plan = planMap.get(exec.getPlanId());
                if (plan != null && plan.getAutoSuiteIds() != null) {
                    // autoSuiteIds 是 JSON 数组字符串，简单解析
                    String sids = plan.getAutoSuiteIds().replaceAll("[\\[\\]\"']", "");
                    for (String sid : sids.split(",")) {
                        try { executedSuiteIds.add(Long.parseLong(sid.trim())); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            long completedCount = executedSuiteIds.size();
            stats.setCompletedSuiteCount(Math.min(completedCount, suiteIds.size()));
            stats.setSuiteCompletionRate(Math.round(completedCount * 1000.0 / suiteIds.size()) / 10.0);
        }

        // ── 回归通过率 ──
        stats.setRegressionPassRate(passRate);

        // ── 健康度评分 ──
        int passRateScore = (int) Math.min(100, passRate);
        int coverageScore = (int) Math.min(100, stats.getApiCoverageRate());
        int stabilityScore = passRateScore;
        int efficiencyScore = weeklyCount > 0 ? Math.min(100, (int) (weeklyCount * 5)) : 0;
        stats.setPassRateScore(passRateScore);
        stats.setCoverageScore(coverageScore);
        stats.setStabilityScore(stabilityScore);
        stats.setEfficiencyScore(efficiencyScore);
        int healthScore = (int) (passRateScore * 0.35 + coverageScore * 0.25 + stabilityScore * 0.25 + efficiencyScore * 0.15);
        stats.setHealthScore(healthScore);

        // ── KPI 环比差值 ──
        stats.setPassRateWeekDelta(calculatePassRateWeekDelta(allExecutions));
        stats.setWeeklyExecDelta(calculateWeeklyExecDelta(allExecutions));
        // 缺陷密度/修复时效/逃逸率暂无独立数据源，保持 null（前端不显示趋势箭头）

        // ── 趋势数据 ──
        trend.setDataUpdateTime(LocalDateTime.now());

        // 最近执行时间
        if (!allExecutions.isEmpty()) {
            allExecutions.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
            trend.setLastExecutionTime(allExecutions.get(0).getCreatedAt());
        }

        // 通过率趋势（近 14 天，按天聚合）
        trend.setPassRateTrend(buildPassRateTrend(allExecutions, 14));

        // 每日执行频次（近 7 天）
        trend.setExecutionFrequency(buildExecutionFrequency(allExecutions, 7));

        // 模块覆盖率
        trend.setModuleCoverage(buildModuleCoverage(projectId, totalApiCount));

        // 质量风险 Top 5
        trend.setQualityRiskTop5(buildQualityRiskTop5(projectId, suiteIds));
        trend.setContinuousFailCount(0);

        // 缺陷趋势（近 4 周）
        trend.setDefectTrend(buildDefectTrend(projectId, planIds));

        // 最近执行记录
        List<RecentExecution> recentExecutions = new ArrayList<>();
        if (!planIds.isEmpty()) {
            LambdaQueryWrapper<TestExecution> recentWrapper = new LambdaQueryWrapper<>();
            recentWrapper.in(TestExecution::getPlanId, planIds)
                    .orderByDesc(TestExecution::getCreatedAt)
                    .last("LIMIT 5");
            List<TestExecution> recent = testExecutionMapper.selectList(recentWrapper);
            for (TestExecution e : recent) {
                RecentExecution re = new RecentExecution();
                BeanUtils.copyProperties(e, re);
                TestPlan plan = planMap.get(e.getPlanId());
                if (plan != null) {
                    re.setPlanName(plan.getName());
                }
                recentExecutions.add(re);
            }
        }

        ProjectDashboardResponse response = new ProjectDashboardResponse();
        response.setProjectId(project.getId());
        response.setProjectName(project.getName());
        response.setProjectDescription(project.getDescription());
        response.setStatus(project.getStatus());
        response.setStats(stats);
        response.setTrend(trend);
        response.setRecentExecutions(recentExecutions);
        return response;
    }

    /**
     * 根据 ID 查找有效项目
     */
    public Project findActiveById(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在：" + projectId);
        }
        return project;
    }

    // ───────────────────── 私有方法 ─────────────────────

    /**
     * 批量统计记录数：一次查询 + 内存分组，消除 N+1
     * <p>仅查询 key 列以减少传输，count 聚合在内存完成
     * ponytail: 内存分组而非 SQL GROUP BY，避免 @Select 原生 SQL 绕过 MyBatis-Plus 逻辑删除；
     *          单项目维度记录数可控，若某表单项目行数达万级需改为 SQL 聚合
     */
    private <T> Map<Long, Long> countByKey(List<Long> keys, BaseMapper<T> mapper, SFunction<T, Long> keyGetter) {
        if (keys == null || keys.isEmpty()) return Collections.emptyMap();
        LambdaQueryWrapper<T> w = new LambdaQueryWrapper<>();
        w.select(keyGetter).in(keyGetter, keys);
        return mapper.selectList(w).stream()
                .map(keyGetter)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(k -> k, Collectors.counting()));
    }

    private List<Long> getAutoSuiteIds(Long projectId) {
        LambdaQueryWrapper<AutoSuite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AutoSuite::getProjectId, projectId).select(AutoSuite::getId);
        return autoSuiteMapper.selectList(wrapper).stream()
                .map(AutoSuite::getId).collect(Collectors.toList());
    }

    private List<Long> getPlanIds(Long projectId) {
        LambdaQueryWrapper<TestPlan> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestPlan::getProjectId, projectId).select(TestPlan::getId);
        return testPlanMapper.selectList(wrapper).stream()
                .map(TestPlan::getId).collect(Collectors.toList());
    }

    private void createSystemModule(Long projectId, String name, String prefix, String description) {
        ApiModule module = new ApiModule();
        module.setProjectId(projectId);
        module.setName(name);
        module.setServicePrefix(prefix);
        module.setDescription(description);
        module.setSourceType("MANUAL");
        module.setIsSystem(1);
        apiModuleMapper.insert(module);
    }

    private void createSystemActionGroup(Long projectId, String name, Long parentId, String description) {
        ActionGroup group = new ActionGroup();
        group.setProjectId(projectId);
        group.setParentId(parentId);
        group.setName(name);
        group.setDescription(description);
        group.setIsSystem(1);
        actionGroupMapper.insert(group);
    }

    private void createSystemKeywordGroup(Long projectId, String name, Long parentId, String description) {
        ApiKeywordGroup group = new ApiKeywordGroup();
        group.setProjectId(projectId);
        group.setParentId(parentId);
        group.setName(name);
        group.setDescription(description);
        group.setIsSystem(1);
        apiKeywordGroupMapper.insert(group);
    }

    private void createSystemDocGroup(Long projectId, String name, Long parentId, String description) {
        ProjectDocGroup group = new ProjectDocGroup();
        group.setProjectId(projectId);
        group.setParentId(parentId);
        group.setName(name);
        group.setDescription(description);
        group.setIsSystem(1);
        projectDocGroupMapper.insert(group);
    }

    private void createSystemRepositoryGroup(Long projectId, String name, Long parentId, String description) {
        CodeRepositoryGroup group = new CodeRepositoryGroup();
        group.setProjectId(projectId);
        group.setParentId(parentId);
        group.setName(name);
        group.setDescription(description);
        group.setIsSystem(1);
        codeRepositoryGroupMapper.insert(group);
    }

    private void createSystemRequirementGroup(Long projectId, String name, Long parentId, String description) {
        RequirementGroup group = new RequirementGroup();
        group.setProjectId(projectId);
        group.setParentId(parentId);
        group.setName(name);
        group.setDescription(description);
        group.setIsSystem(1);
        requirementGroupMapper.insert(group);
    }

    private ProjectResponse toResponse(Project project) {
        ProjectResponse response = new ProjectResponse();
        BeanUtils.copyProperties(project, response);
        return response;
    }

    /**
     * 构建通过率趋势（按天聚合）
     */
    private List<DashboardTrendResponse.TrendPoint> buildPassRateTrend(
            List<TestExecution> executions, int days) {
        List<DashboardTrendResponse.TrendPoint> points = new ArrayList<>();
        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");

        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

            long dayPassed = 0, dayFailed = 0;
            for (TestExecution e : executions) {
                if (e.getCreatedAt() != null
                        && !e.getCreatedAt().isBefore(dayStart)
                        && e.getCreatedAt().isBefore(dayEnd)) {
                    if (e.getPassedCases() != null) dayPassed += e.getPassedCases();
                    if (e.getFailedCases() != null) dayFailed += e.getFailedCases();
                }
            }
            long dayTotal = dayPassed + dayFailed;
            double rate = dayTotal > 0 ? Math.round(dayPassed * 1000.0 / dayTotal) / 10.0 : 0.0;
            points.add(new DashboardTrendResponse.TrendPoint(date.format(fmt), rate));
        }
        return points;
    }

    /**
     * 构建每日执行频次（近 N 天，通过/失败堆叠）
     */
    private List<DashboardTrendResponse.ExecutionFreqItem> buildExecutionFrequency(
            List<TestExecution> executions, int days) {
        List<DashboardTrendResponse.ExecutionFreqItem> items = new ArrayList<>();
        LocalDate today = LocalDate.now();
        String[] dayNames = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

            int dayPassed = 0, dayFailed = 0;
            for (TestExecution e : executions) {
                if (e.getCreatedAt() != null
                        && !e.getCreatedAt().isBefore(dayStart)
                        && e.getCreatedAt().isBefore(dayEnd)) {
                    dayPassed += e.getPassedCases() != null ? e.getPassedCases() : 0;
                    dayFailed += e.getFailedCases() != null ? e.getFailedCases() : 0;
                }
            }
            String dayName = dayNames[date.getDayOfWeek().getValue() % 7];
            items.add(new DashboardTrendResponse.ExecutionFreqItem(dayName, dayPassed, dayFailed));
        }
        return items;
    }

    /**
     * 构建模块覆盖率
     */
    private List<DashboardTrendResponse.ModuleCoverage> buildModuleCoverage(
            Long projectId, long totalApiCount) {
        List<DashboardTrendResponse.ModuleCoverage> coverages = new ArrayList<>();

        LambdaQueryWrapper<ApiModule> moduleWrapper = new LambdaQueryWrapper<>();
        moduleWrapper.eq(ApiModule::getProjectId, projectId)
                .eq(ApiModule::getIsSystem, 0);
        List<ApiModule> modules = apiModuleMapper.selectList(moduleWrapper);

        for (ApiModule module : modules) {
            LambdaQueryWrapper<Api> apiWrapper = new LambdaQueryWrapper<>();
            apiWrapper.eq(Api::getModuleId, module.getId());
            long count = apiMapper.selectCount(apiWrapper);
            double pct = totalApiCount > 0 ? Math.round(count * 1000.0 / totalApiCount) / 10.0 : 0.0;
            coverages.add(new DashboardTrendResponse.ModuleCoverage(module.getName(), count, pct));
        }

        // 按接口数量降序排列
        coverages.sort((a, b) -> Long.compare(b.getCount(), a.getCount()));
        return coverages;
    }

    /**
     * 构建质量风险 Top 5（近 30 天失败最多的自动化用例）
     */
    private List<DashboardTrendResponse.QualityRisk> buildQualityRiskTop5(
            Long projectId, List<Long> autoSuiteIds) {
        List<DashboardTrendResponse.QualityRisk> risks = new ArrayList<>();

        if (autoSuiteIds.isEmpty()) return risks;

        // 查询近 30 天的执行记录
        LocalDateTime thirtyDaysAgo = LocalDate.now().minusDays(30).atStartOfDay();
        List<Long> planIds = getPlanIds(projectId);
        if (planIds.isEmpty()) return risks;

        LambdaQueryWrapper<TestExecution> execWrapper = new LambdaQueryWrapper<>();
        execWrapper.in(TestExecution::getPlanId, planIds)
                .ge(TestExecution::getCreatedAt, thirtyDaysAgo);
        List<TestExecution> recentExecs = testExecutionMapper.selectList(execWrapper);

        if (recentExecs.isEmpty()) return risks;

        List<Long> execIds = recentExecs.stream().map(TestExecution::getId).collect(Collectors.toList());

        // 查询这些执行记录中失败的自动化用例
        LambdaQueryWrapper<TestResult> resultWrapper = new LambdaQueryWrapper<>();
        resultWrapper.in(TestResult::getExecutionId, execIds)
                .in(TestResult::getStatus, Arrays.asList("FAILED", "ERROR"));
        List<TestResult> failedResults = testResultMapper.selectList(resultWrapper);

        // 按自动化用例分组统计失败次数
        Map<Long, Integer> failCountMap = new HashMap<>();
        for (TestResult r : failedResults) {
            failCountMap.merge(r.getAutoCaseId(), 1, Integer::sum);
        }

        // 排序取 Top 5
        List<Map.Entry<Long, Integer>> sorted = failCountMap.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(5)
                .collect(Collectors.toList());

        int rank = 1;
        for (Map.Entry<Long, Integer> entry : sorted) {
            AutoCase tc = autoCaseMapper.selectById(entry.getKey());
            if (tc == null) continue;
            AutoSuite suite = autoSuiteMapper.selectById(tc.getAutoSuiteId());
            String suiteName = suite != null ? suite.getName() : "未知自动化套件";

            // 计算该自动化用例的总执行次数和失败率
            LambdaQueryWrapper<TestResult> totalWrapper = new LambdaQueryWrapper<>();
            totalWrapper.in(TestResult::getExecutionId, execIds)
                    .eq(TestResult::getAutoCaseId, entry.getKey());
            long totalExecs = testResultMapper.selectCount(totalWrapper);
            double failRate = totalExecs > 0 ? Math.round(entry.getValue() * 1000.0 / totalExecs) / 10.0 : 0.0;

            risks.add(new DashboardTrendResponse.QualityRisk(
                    rank++, tc.getName(), suiteName, entry.getValue(), failRate));
        }

        return risks;
    }

    /**
     * 构建缺陷趋势（近 4 周，按周聚合新增/已修复缺陷数）
     *
     * <p>新增缺陷：该周首次失败的自动化用例数；已修复：之前失败但本周通过的自动化用例数
     */
    private List<DashboardTrendResponse.DefectTrendItem> buildDefectTrend(
            Long projectId, List<Long> planIds) {
        List<DashboardTrendResponse.DefectTrendItem> trends = new ArrayList<>();
        if (planIds.isEmpty()) return trends;

        LocalDate today = LocalDate.now();
        LocalDateTime fourWeeksAgo = today.minusWeeks(4).atStartOfDay();

        // 查询近 4 周执行记录
        LambdaQueryWrapper<TestExecution> execWrapper = new LambdaQueryWrapper<>();
        execWrapper.in(TestExecution::getPlanId, planIds)
                .ge(TestExecution::getCreatedAt, fourWeeksAgo);
        List<TestExecution> recentExecs = testExecutionMapper.selectList(execWrapper);

        if (recentExecs.isEmpty()) {
            // 无数据时返回 4 周空占位
            for (int i = 3; i >= 0; i--) {
                trends.add(new DashboardTrendResponse.DefectTrendItem("第" + (4 - i) + "周", 0, 0));
            }
            return trends;
        }

        List<Long> execIds = recentExecs.stream().map(TestExecution::getId).collect(Collectors.toList());

        // 查询所有相关测试结果
        LambdaQueryWrapper<TestResult> resultWrapper = new LambdaQueryWrapper<>();
        resultWrapper.in(TestResult::getExecutionId, execIds);
        List<TestResult> allResults = testResultMapper.selectList(resultWrapper);

        // 构建 executionId -> createdAt 映射
        Map<Long, LocalDateTime> execTimeMap = new HashMap<>();
        for (TestExecution exec : recentExecs) {
            execTimeMap.put(exec.getId(), exec.getCreatedAt());
        }

        // 按周分组统计
        for (int i = 3; i >= 0; i--) {
            LocalDate weekStart = today.minusWeeks(i).with(DayOfWeek.MONDAY).atStartOfDay().toLocalDate();
            LocalDateTime ws = weekStart.atStartOfDay();
            LocalDateTime we = weekStart.plusDays(7).atStartOfDay();

            // 本周执行的 ID 集合
            Set<Long> weekExecIds = new HashSet<>();
            for (TestExecution exec : recentExecs) {
                if (exec.getCreatedAt() != null
                        && !exec.getCreatedAt().isBefore(ws)
                        && exec.getCreatedAt().isBefore(we)) {
                    weekExecIds.add(exec.getId());
                }
            }

            // 本周新增缺陷：首次失败的自动化用例
            Set<Long> weekFailedCases = new HashSet<>();
            for (TestResult r : allResults) {
                if (weekExecIds.contains(r.getExecutionId())
                        && ("FAILED".equals(r.getStatus()) || "ERROR".equals(r.getStatus()))) {
                    weekFailedCases.add(r.getAutoCaseId());
                }
            }

            // 统计本周之前失败的自动化用例
            Set<Long> prevFailedCases = new HashSet<>();
            for (TestResult r : allResults) {
                LocalDateTime execTime = execTimeMap.get(r.getExecutionId());
                if (execTime != null && execTime.isBefore(ws)
                        && ("FAILED".equals(r.getStatus()) || "ERROR".equals(r.getStatus()))) {
                    prevFailedCases.add(r.getAutoCaseId());
                }
            }

            // 本周通过的自动化用例
            Set<Long> weekPassedCases = new HashSet<>();
            for (TestResult r : allResults) {
                if (weekExecIds.contains(r.getExecutionId()) && "PASSED".equals(r.getStatus())) {
                    weekPassedCases.add(r.getAutoCaseId());
                }
            }

            // 已修复 = 之前失败但本周通过的自动化用例
            int fixedCount = 0;
            for (Long autoCaseId : weekPassedCases) {
                if (prevFailedCases.contains(autoCaseId) && !weekFailedCases.contains(autoCaseId)) {
                    fixedCount++;
                }
            }

            // 新增缺陷 = 本周新失败的自动化用例（排除之前已失败的，取首次出现）
            int newCount = 0;
            for (Long autoCaseId : weekFailedCases) {
                if (!prevFailedCases.contains(autoCaseId)) {
                    newCount++;
                }
            }

            trends.add(new DashboardTrendResponse.DefectTrendItem(
                    "第" + (4 - i) + "周", newCount, fixedCount));
        }

        return trends;
    }

    /**
     * 计算通过率周环比（近 7 天均值 vs 前 7 天均值）
     *
     * @return 差值（百分点），null 表示数据不足无法计算
     */
    private Double calculatePassRateWeekDelta(List<TestExecution> executions) {
        LocalDate today = LocalDate.now();
        LocalDateTime thisWeekStart = today.minusDays(7).atStartOfDay();
        LocalDateTime prevWeekStart = today.minusDays(14).atStartOfDay();

        long thisPassed = 0, thisFailed = 0, prevPassed = 0, prevFailed = 0;
        for (TestExecution e : executions) {
            if (e.getCreatedAt() == null) continue;
            if (!e.getCreatedAt().isBefore(thisWeekStart)) {
                if (e.getPassedCases() != null) thisPassed += e.getPassedCases();
                if (e.getFailedCases() != null) thisFailed += e.getFailedCases();
            } else if (!e.getCreatedAt().isBefore(prevWeekStart)) {
                if (e.getPassedCases() != null) prevPassed += e.getPassedCases();
                if (e.getFailedCases() != null) prevFailed += e.getFailedCases();
            }
        }

        long thisTotal = thisPassed + thisFailed;
        long prevTotal = prevPassed + prevFailed;
        if (thisTotal == 0 || prevTotal == 0) return null;

        double thisRate = Math.round(thisPassed * 1000.0 / thisTotal) / 10.0;
        double prevRate = Math.round(prevPassed * 1000.0 / prevTotal) / 10.0;
        return Math.round((thisRate - prevRate) * 10.0) / 10.0;
    }

    /**
     * 计算本周执行次数环比（本周 vs 上周）
     *
     * @return 差值（次数），null 表示无上周数据
     */
    private Long calculateWeeklyExecDelta(List<TestExecution> executions) {
        LocalDate today = LocalDate.now();
        LocalDateTime thisWeekStart = today.with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime lastWeekStart = thisWeekStart.minusDays(7);

        long thisCount = 0, lastCount = 0;
        for (TestExecution e : executions) {
            if (e.getCreatedAt() == null) continue;
            if (!e.getCreatedAt().isBefore(thisWeekStart)) {
                thisCount++;
            } else if (!e.getCreatedAt().isBefore(lastWeekStart)) {
                lastCount++;
            }
        }

        return lastCount > 0 ? thisCount - lastCount : null;
    }
}
