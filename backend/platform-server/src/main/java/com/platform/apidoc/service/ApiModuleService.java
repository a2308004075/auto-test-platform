/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description API 模块管理服务
 */
package com.platform.apidoc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.apidoc.dto.ApiModuleCreateRequest;
import com.platform.apidoc.dto.ApiModuleResponse;
import com.platform.apidoc.dto.ApiModuleUpdateRequest;
import com.platform.apidoc.entity.Api;
import com.platform.apidoc.mapper.ApiMapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.knowledge.event.ProjectMaterialChangedEvent;
import com.platform.knowledge.service.KnowledgeMaterialCollector;
import com.platform.project.entity.ApiModule;
import com.platform.project.mapper.ApiModuleMapper;
import com.platform.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 接口分组管理服务
 */
@Service
@RequiredArgsConstructor
public class ApiModuleService {

    private final ApiModuleMapper apiModuleMapper;
    private final ApiMapper apiMapper;
    private final ProjectService projectService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 查询项目下的分组列表（扁平列表，前端自行建树）
     * <p>apiCount 包含子分组的接口数（自底向上聚合）。
     */
    public List<ApiModuleResponse> listByProject(Long projectId) {
        LambdaQueryWrapper<ApiModule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiModule::getProjectId, projectId);
        wrapper.orderByDesc(ApiModule::getIsSystem, ApiModule::getCreatedAt);

        List<ApiModule> list = apiModuleMapper.selectList(wrapper);

        // 统计每个分组的直接接口数
        Map<Long, Integer> directCountMap = new LinkedHashMap<>();
        for (ApiModule module : list) {
            LambdaQueryWrapper<Api> apiWrapper = new LambdaQueryWrapper<>();
            apiWrapper.eq(Api::getModuleId, module.getId());
            directCountMap.put(module.getId(), apiMapper.selectCount(apiWrapper).intValue());
        }

        // 建树后自底向上聚合子分组接口数
        Map<Long, List<ApiModule>> childrenMap = list.stream()
                .filter(m -> m.getParentId() != null)
                .collect(Collectors.groupingBy(ApiModule::getParentId));

        Map<Long, Integer> totalCountMap = new LinkedHashMap<>();
        for (ApiModule module : list) {
            totalCountMap.put(module.getId(), aggregateCount(module.getId(), directCountMap, childrenMap));
        }

        List<ApiModuleResponse> result = new ArrayList<>();
        for (ApiModule module : list) {
            ApiModuleResponse resp = toResponse(module);
            resp.setApiCount(totalCountMap.getOrDefault(module.getId(), 0));
            result.add(resp);
        }
        return result;
    }

    /**
     * 查询项目下所有分组，返回以分组 ID 为 key 的 Map
     */
    public Map<Long, ApiModule> getModuleMap(Long projectId) {
        LambdaQueryWrapper<ApiModule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiModule::getProjectId, projectId);
        return apiModuleMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(ApiModule::getId, m -> m, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 解析分组的有效服务前缀：优先使用自身，否则向上追溯父分组
     */
    public String resolveServicePrefix(Long moduleId, Map<Long, ApiModule> moduleMap) {
        ApiModule module = moduleMap.get(moduleId);
        if (module == null) {
            return "";
        }
        if (StringUtils.hasText(module.getServicePrefix())) {
            return module.getServicePrefix();
        }
        if (module.getParentId() == null) {
            return "";
        }
        return resolveServicePrefix(module.getParentId(), moduleMap);
    }

    /**
     * 获取指定分组及其所有子孙分组的 ID 集合（用于接口列表过滤）
     */
    public Set<Long> getDescendantModuleIds(Long moduleId) {
        Set<Long> result = new LinkedHashSet<>();
        result.add(moduleId);
        collectDescendants(moduleId, result);
        return result;
    }

    /**
     * 创建分组
     */
    public ApiModuleResponse create(ApiModuleCreateRequest request) {
        projectService.findActiveById(request.getProjectId());

        // 检查同项目下是否存在同名分组
        checkNameDuplicate(request.getProjectId(), request.getName(), null);

        ApiModule module = new ApiModule();
        module.setProjectId(request.getProjectId());
        module.setParentId(request.getParentId());
        module.setName(request.getName());
        module.setServicePrefix(request.getServicePrefix());
        module.setDescription(request.getDescription());
        module.setSourceType("MANUAL");
        module.setIsSystem(0);

        apiModuleMapper.insert(module);

        // 知识库同步：模块创建（采集粒度 = 1 模块 = 1 知识库文档）
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                module.getProjectId(), KnowledgeMaterialCollector.SOURCE_API_MODULE, module.getId()));

        return toResponse(module);
    }

    /**
     * 更新分组
     */
    public ApiModuleResponse update(Long moduleId, ApiModuleUpdateRequest request) {
        ApiModule module = findById(moduleId);

        if (Integer.valueOf(1).equals(module.getIsSystem())) {
            throw new BusinessException(ErrorCode.API_MODULE_SYSTEM, "系统分组不允许修改");
        }

        if (StringUtils.hasText(request.getName())) {
            checkNameDuplicate(module.getProjectId(), request.getName(), moduleId);
            module.setName(request.getName());
        }
        if (request.getServicePrefix() != null) {
            module.setServicePrefix(request.getServicePrefix());
        }
        if (request.getDescription() != null) {
            module.setDescription(request.getDescription());
        }
        // parentId：始终应用（null = 移到根级）
        Long newParentId = request.getParentId();
        if (newParentId != null && newParentId.equals(module.getId())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "不能将分组设为自身的子分组");
        }
        if (newParentId != null && getDescendantModuleIds(module.getId()).contains(newParentId)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "不能将分组移动到其子分组下");
        }
        if (!Objects.equals(newParentId, module.getParentId())) {
            checkNameDuplicate(module.getProjectId(), module.getName(), module.getId());
        }
        module.setParentId(newParentId);

        apiModuleMapper.updateById(module);

        // 知识库同步：模块元数据变化，重新采集该模块
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                module.getProjectId(), KnowledgeMaterialCollector.SOURCE_API_MODULE, moduleId));

        return toResponse(module);
    }

    /**
     * 删除分组（系统分组不允许删除）
     */
    public void delete(Long moduleId) {
        ApiModule module = findById(moduleId);

        if (Integer.valueOf(1).equals(module.getIsSystem())) {
            throw new BusinessException(ErrorCode.API_MODULE_SYSTEM, "系统分组不允许删除");
        }

        // 检查是否有子分组
        LambdaQueryWrapper<ApiModule> childWrapper = new LambdaQueryWrapper<>();
        childWrapper.eq(ApiModule::getParentId, moduleId);
        if (apiModuleMapper.selectCount(childWrapper) > 0) {
            throw new BusinessException(ErrorCode.API_MODULE_HAS_APIS, "分组下存在子分组，请先删除子分组");
        }

        // 检查分组下是否有接口
        LambdaQueryWrapper<Api> apiWrapper = new LambdaQueryWrapper<>();
        apiWrapper.eq(Api::getModuleId, moduleId);
        if (apiMapper.selectCount(apiWrapper) > 0) {
            throw new BusinessException(ErrorCode.API_MODULE_HAS_APIS, "分组下存在接口，请先删除接口");
        }

        apiModuleMapper.deleteById(moduleId);

        // 知识库同步：模块删除，同步引擎采集不到将移除对应知识库文档
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                module.getProjectId(), KnowledgeMaterialCollector.SOURCE_API_MODULE, moduleId));
    }

    /**
     * 获取分组详情
     */
    public ApiModuleResponse getById(Long moduleId) {
        ApiModule module = findById(moduleId);
        ApiModuleResponse resp = toResponse(module);
        LambdaQueryWrapper<Api> apiWrapper = new LambdaQueryWrapper<>();
        apiWrapper.eq(Api::getModuleId, moduleId);
        resp.setApiCount(apiMapper.selectCount(apiWrapper).intValue());
        return resp;
    }

    // ───────────────────── 私有方法 ─────────────────────

    /**
     * 递归聚合分组及其子分组的接口数
     */
    private int aggregateCount(Long moduleId, Map<Long, Integer> directCountMap,
                                Map<Long, List<ApiModule>> childrenMap) {
        int count = directCountMap.getOrDefault(moduleId, 0);
        List<ApiModule> children = childrenMap.get(moduleId);
        if (children != null) {
            for (ApiModule child : children) {
                count += aggregateCount(child.getId(), directCountMap, childrenMap);
            }
        }
        return count;
    }

    /**
     * 递归收集子孙分组 ID
     */
    private void collectDescendants(Long parentId, Set<Long> collected) {
        LambdaQueryWrapper<ApiModule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiModule::getParentId, parentId);
        List<ApiModule> children = apiModuleMapper.selectList(wrapper);
        for (ApiModule child : children) {
            collected.add(child.getId());
            collectDescendants(child.getId(), collected);
        }
    }

    /**
     * 检查同项目下是否存在同名分组（排除指定 ID）
     */
    private void checkNameDuplicate(Long projectId, String name, Long excludeId) {
        LambdaQueryWrapper<ApiModule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiModule::getProjectId, projectId);
        wrapper.eq(ApiModule::getName, name);
        if (excludeId != null) {
            wrapper.ne(ApiModule::getId, excludeId);
        }
        if (apiModuleMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.API_MODULE_NAME_DUPLICATE,
                    "分组名称已存在：" + name);
        }
    }

    private ApiModule findById(Long moduleId) {
        ApiModule module = apiModuleMapper.selectById(moduleId);
        if (module == null) {
            throw new BusinessException(ErrorCode.API_MODULE_NOT_FOUND, "分组不存在：" + moduleId);
        }
        return module;
    }

    private ApiModuleResponse toResponse(ApiModule module) {
        ApiModuleResponse resp = new ApiModuleResponse();
        BeanUtils.copyProperties(module, resp);
        return resp;
    }
}
