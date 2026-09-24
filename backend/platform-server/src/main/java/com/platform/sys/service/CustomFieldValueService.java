/**
 * @author HXN
 * @date 2026-09-16
 * @description 自定义字段值服务（保存/读取 + 渲染选项组装）
 */
package com.platform.sys.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.auth.entity.User;
import com.platform.auth.mapper.UserMapper;
import com.platform.environment.entity.Environment;
import com.platform.environment.mapper.EnvironmentMapper;
import com.platform.sys.dto.CustomFieldRenderDTO;
import com.platform.sys.entity.CustomField;
import com.platform.sys.entity.CustomFieldValue;
import com.platform.sys.mapper.CustomFieldMapper;
import com.platform.sys.mapper.CustomFieldValueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 自定义字段值服务
 *
 * <p>负责：业务实体（缺陷/需求）自定义字段值的保存与读取；
 * 渲染 DTO 的选项组装（select/user/environment 统一转为 label/value 列表）
 *
 * <p>同一业务实体在 create/edit 两个视图的字段配置可能不同，但同一 fieldKey
 * 的值互通（通过 fieldKey + entityId 定位，而非视图内的 fieldId）；
 * 读取时优先取 edit 视图配置行的值（最新写入位置），create 视图配置仅作存量兜底。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomFieldValueService {

    private final CustomFieldMapper customFieldMapper;
    private final CustomFieldValueMapper customFieldValueMapper;
    private final UserMapper userMapper;
    private final EnvironmentMapper environmentMapper;

    /**
     * 组装渲染 DTO 的选项列表
     *
     * <p>按字段类型从对应数据源取数：
     * <ul>
     *   <li>select：解析 optionsJson</li>
     *   <li>user：查询启用用户（排除 superAdmin）</li>
     *   <li>environment：查询项目环境列表</li>
     * </ul>
     */
    public void fillOptions(Long projectId, List<CustomFieldRenderDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        // user / environment 选项按需加载，同一请求内复用
        List<CustomFieldRenderDTO.FieldOption> userOptions = null;
        List<CustomFieldRenderDTO.FieldOption> envOptions = null;

        for (CustomFieldRenderDTO dto : dtos) {
            String type = dto.getFieldType();
            if ("select".equals(type)) {
                dto.setOptions(parseOptions(dto.getOptionsJson()));
            } else if ("user".equals(type)) {
                if (userOptions == null) {
                    userOptions = loadUserOptions();
                }
                dto.setOptions(userOptions);
            } else if ("environment".equals(type)) {
                if (envOptions == null) {
                    envOptions = loadEnvironmentOptions(projectId);
                }
                dto.setOptions(envOptions);
            }
        }
    }

    /**
     * 保存业务实体的自定义字段值（全量覆盖式：以当前视图字段配置为准）
     *
     * @param projectId 项目 ID
     * @param module    模块标识（defect/requirement）
     * @param viewType  视图（create/edit）
     * @param entityId  业务实体 ID
     * @param values    fieldKey -> 字段值（字符串形式；空值跳过）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveValues(Long projectId, String module, String viewType, Long entityId, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        // 当前视图启用的字段配置（fieldKey -> fieldId）
        LambdaQueryWrapper<CustomField> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomField::getProjectId, projectId)
                .eq(CustomField::getModule, module)
                .eq(CustomField::getViewType, viewType)
                .eq(CustomField::getIsActive, 1);
        List<CustomField> fields = customFieldMapper.selectList(wrapper);
        Map<String, Long> fieldIdMap = fields.stream()
                .collect(Collectors.toMap(CustomField::getFieldKey, CustomField::getId, (a, b) -> a));

        for (Map.Entry<String, String> entry : values.entrySet()) {
            String fieldKey = entry.getKey();
            String value = entry.getValue();
            Long fieldId = fieldIdMap.get(fieldKey);
            if (fieldId == null) {
                // 字段配置已删除或不在当前视图，忽略
                continue;
            }
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            upsertValue(fieldId, module, entityId, value.trim());
        }
    }

    /**
     * 读取业务实体的自定义字段值（跨视图：按 module 查全部字段定义后组装 fieldKey -> value）
     *
     * <p>读取不区分 create/edit 视图：同一 fieldKey 在两个视图配置中的 fieldId 不同，
     * 但值只需读取一份（优先取 edit 视图配置的 fieldId，其为最新值的写入位置；create 仅作存量兜底）。
     */
    public Map<String, String> loadValues(Long projectId, String module, Long entityId) {
        // 该项目 + 模块下的全部字段（含 create/edit 两视图，is_active=1；viewType 降序让 edit 优先）
        LambdaQueryWrapper<CustomField> fieldWrapper = new LambdaQueryWrapper<>();
        fieldWrapper.eq(CustomField::getProjectId, projectId)
                .eq(CustomField::getModule, module)
                .eq(CustomField::getIsActive, 1)
                .orderByDesc(CustomField::getViewType);
        List<CustomField> fields = customFieldMapper.selectList(fieldWrapper);
        if (fields.isEmpty()) {
            return new HashMap<>();
        }

        // entity 的全部字段值（fieldId -> value）
        LambdaQueryWrapper<CustomFieldValue> valueWrapper = new LambdaQueryWrapper<>();
        valueWrapper.eq(CustomFieldValue::getModule, module)
                .eq(CustomFieldValue::getEntityId, entityId);
        Map<Long, String> valueMap = customFieldValueMapper.selectList(valueWrapper).stream()
                .collect(Collectors.toMap(CustomFieldValue::getFieldId, CustomFieldValue::getFieldValue, (a, b) -> a));

        // fieldKey -> value（fields 已按 viewType 降序，同一 fieldKey 多视图配置时优先取 edit 行的值）
        Map<String, String> result = new HashMap<>();
        for (CustomField field : fields) {
            String value = valueMap.get(field.getId());
            if (value != null && !result.containsKey(field.getFieldKey())) {
                result.put(field.getFieldKey(), value);
            }
        }
        return result;
    }

    /**
     * 批量读取多个业务实体的自定义字段值（列表页用，避免逐条 N+1 查询）
     *
     * <p>语义与 {@link #loadValues} 一致：跨视图读取，同一 fieldKey 多视图配置时优先取 edit 视图行的值
     *
     * @return entityId -> (fieldKey -> value)
     */
    public Map<Long, Map<String, String>> loadValuesBatch(Long projectId, String module, List<Long> entityIds) {
        Map<Long, Map<String, String>> result = new HashMap<>();
        if (entityIds == null || entityIds.isEmpty()) {
            return result;
        }
        // 该项目 + 模块下的全部字段（含 create/edit 两视图，is_active=1；viewType 降序让 edit 优先）
        LambdaQueryWrapper<CustomField> fieldWrapper = new LambdaQueryWrapper<>();
        fieldWrapper.eq(CustomField::getProjectId, projectId)
                .eq(CustomField::getModule, module)
                .eq(CustomField::getIsActive, 1)
                .orderByDesc(CustomField::getViewType);
        List<CustomField> fields = customFieldMapper.selectList(fieldWrapper);
        if (fields.isEmpty()) {
            return result;
        }

        // 全部实体的字段值，按 entityId 分组（fieldId -> value）
        LambdaQueryWrapper<CustomFieldValue> valueWrapper = new LambdaQueryWrapper<>();
        valueWrapper.eq(CustomFieldValue::getModule, module)
                .in(CustomFieldValue::getEntityId, entityIds);
        Map<Long, Map<Long, String>> valueByEntity = customFieldValueMapper.selectList(valueWrapper).stream()
                .filter(v -> v.getFieldValue() != null)
                .collect(Collectors.groupingBy(CustomFieldValue::getEntityId,
                        Collectors.toMap(CustomFieldValue::getFieldId, CustomFieldValue::getFieldValue, (a, b) -> a)));

        for (Long entityId : entityIds) {
            Map<Long, String> valueMap = valueByEntity.getOrDefault(entityId, new HashMap<>());
            Map<String, String> entityResult = new HashMap<>();
            for (CustomField field : fields) {
                String value = valueMap.get(field.getId());
                if (value != null && !entityResult.containsKey(field.getFieldKey())) {
                    entityResult.put(field.getFieldKey(), value);
                }
            }
            result.put(entityId, entityResult);
        }
        return result;
    }

    /**
     * 删除业务实体的全部自定义字段值（业务实体删除时级联清理）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByEntity(String module, Long entityId) {
        LambdaQueryWrapper<CustomFieldValue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomFieldValue::getModule, module)
                .eq(CustomFieldValue::getEntityId, entityId);
        customFieldValueMapper.delete(wrapper);
    }

    // ===== 私有方法 =====

    private void upsertValue(Long fieldId, String module, Long entityId, String value) {
        LambdaQueryWrapper<CustomFieldValue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomFieldValue::getFieldId, fieldId)
                .eq(CustomFieldValue::getEntityId, entityId);
        CustomFieldValue existing = customFieldValueMapper.selectOne(wrapper);
        if (existing != null) {
            existing.setFieldValue(value);
            customFieldValueMapper.updateById(existing);
        } else {
            CustomFieldValue newValue = new CustomFieldValue();
            newValue.setFieldId(fieldId);
            newValue.setModule(module);
            newValue.setEntityId(entityId);
            newValue.setFieldValue(value);
            customFieldValueMapper.insert(newValue);
        }
    }

    private List<CustomFieldRenderDTO.FieldOption> parseOptions(String optionsJson) {
        List<CustomFieldRenderDTO.FieldOption> result = new ArrayList<>();
        if (!StringUtils.hasText(optionsJson)) {
            return result;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, String>> rows = mapper.readValue(optionsJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            for (Map<String, String> row : rows) {
                result.add(new CustomFieldRenderDTO.FieldOption(row.get("label"), row.get("value")));
            }
        } catch (Exception e) {
            log.warn("解析自定义字段选项 JSON 失败: {}", optionsJson, e);
        }
        return result;
    }

    private List<CustomFieldRenderDTO.FieldOption> loadUserOptions() {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getIsActive, 1)
                .orderByAsc(User::getId);
        return userMapper.selectList(wrapper).stream()
                .filter(u -> !"superAdmin".equals(u.getUsername()))
                .map(u -> new CustomFieldRenderDTO.FieldOption(u.getDisplayName(), String.valueOf(u.getId())))
                .collect(Collectors.toList());
    }

    private List<CustomFieldRenderDTO.FieldOption> loadEnvironmentOptions(Long projectId) {
        if (projectId == null) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<Environment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Environment::getProjectId, projectId)
                .orderByAsc(Environment::getId);
        return environmentMapper.selectList(wrapper).stream()
                .map(e -> new CustomFieldRenderDTO.FieldOption(e.getName(), String.valueOf(e.getId())))
                .collect(Collectors.toList());
    }
}
