/**
 * @author HXN
 * @date 2026-08-30
 * @description 自定义字段管理服务
 */
package com.platform.sys.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.sys.dto.CustomFieldCreateRequest;
import com.platform.sys.dto.CustomFieldListItem;
import com.platform.sys.dto.CustomFieldRenderDTO;
import com.platform.sys.entity.CustomField;
import com.platform.sys.mapper.CustomFieldMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 自定义字段管理服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomFieldService {

    private final CustomFieldMapper customFieldMapper;
    private final CustomFieldValueService customFieldValueService;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 合法的字段类型集合
     */
    private static final Set<String> VALID_FIELD_TYPES = new HashSet<>(Arrays.asList(
            "text", "textarea", "select", "datetime", "number", "user", "environment"));

    /**
     * 管理页列表（按 sortNo 排序）
     */
    public List<CustomFieldListItem> listByConfig(Long projectId, String module, String viewType) {
        LambdaQueryWrapper<CustomField> wrapper = new LambdaQueryWrapper<>();
        if (projectId != null) {
            wrapper.eq(CustomField::getProjectId, projectId);
        }
        if (module != null && !module.isEmpty()) {
            wrapper.eq(CustomField::getModule, module);
        }
        if (viewType != null && !viewType.isEmpty()) {
            wrapper.eq(CustomField::getViewType, viewType);
        }
        wrapper.orderByAsc(CustomField::getSortNo)
                .orderByAsc(CustomField::getId);
        List<CustomField> fields = customFieldMapper.selectList(wrapper);
        return fields.stream().map(this::toListItem).collect(Collectors.toList());
    }

    /**
     * 编辑页渲染（仅启用的，按 sortNo 排序；下拉类字段由服务层统一组装 options）
     */
    public List<CustomFieldRenderDTO> listForRender(Long projectId, String module, String viewType) {
        LambdaQueryWrapper<CustomField> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomField::getProjectId, projectId)
                .eq(CustomField::getModule, module)
                .eq(CustomField::getViewType, viewType)
                .eq(CustomField::getIsActive, 1)
                .orderByAsc(CustomField::getSortNo)
                .orderByAsc(CustomField::getId);
        List<CustomField> fields = customFieldMapper.selectList(wrapper);
        List<CustomFieldRenderDTO> dtos = fields.stream().map(this::toRenderDTO).collect(Collectors.toList());
        customFieldValueService.fillOptions(projectId, dtos);
        return dtos;
    }

    /**
     * 新建字段（fieldKey 由系统自动生成，用户无需填写）
     */
    @Transactional(rollbackFor = Exception.class)
    public CustomFieldListItem create(CustomFieldCreateRequest request) {
        validateFieldType(request);

        CustomField field = new CustomField();
        BeanUtils.copyProperties(request, field);
        field.setFieldKey("field_" + UUID.randomUUID().toString().replace("-", ""));
        if (field.getSortNo() == null) {
            field.setSortNo(0);
        }
        if (field.getIsRequired() == null) {
            field.setIsRequired(0);
        }
        field.setIsActive(1);
        customFieldMapper.insert(field);
        return toListItem(field);
    }

    /**
     * 更新字段（fieldKey 不允许修改，保持原值）
     */
    @Transactional(rollbackFor = Exception.class)
    public CustomFieldListItem update(Long id, CustomFieldCreateRequest request) {
        validateFieldType(request);

        CustomField field = customFieldMapper.selectById(id);
        if (field == null) {
            throw new BusinessException(ErrorCode.CUSTOM_FIELD_NOT_FOUND, "字段不存在");
        }

        BeanUtils.copyProperties(request, field);
        customFieldMapper.updateById(field);
        return toListItem(field);
    }

    /**
     * 删除字段（软删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        CustomField field = customFieldMapper.selectById(id);
        if (field == null) {
            throw new BusinessException(ErrorCode.CUSTOM_FIELD_NOT_FOUND, "字段不存在");
        }
        customFieldMapper.deleteById(id);
    }

    /**
     * 批量排序（拖拽调整顺序：按传入顺序整体重写 sortNo 为连续序号 1、2、3…，
     * orderedIds 必须恰好覆盖同项目+模块+视图下的全部字段）
     *
     * @param orderedIds 按目标顺序排列的字段 ID 列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void sort(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "字段 ID 列表不能为空");
        }
        if (new HashSet<>(orderedIds).size() != orderedIds.size()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "字段 ID 列表存在重复");
        }

        List<CustomField> fields = customFieldMapper.selectBatchIds(orderedIds);
        if (fields.size() != orderedIds.size()) {
            throw new BusinessException(ErrorCode.CUSTOM_FIELD_NOT_FOUND, "存在无效的字段 ID");
        }

        // 同一配置校验（项目 + 模块 + 视图必须一致，避免跨视图错排）
        Long projectId = fields.get(0).getProjectId();
        String module = fields.get(0).getModule();
        String viewType = fields.get(0).getViewType();
        boolean sameConfig = fields.stream().allMatch(f ->
                projectId.equals(f.getProjectId())
                        && module.equals(f.getModule())
                        && viewType.equals(f.getViewType()));
        if (!sameConfig) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "排序字段必须属于同一项目/模块/视图");
        }

        // 完整性校验：必须覆盖该配置下全部字段（避免遗留字段 sortNo 与新序号冲突）
        LambdaQueryWrapper<CustomField> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(CustomField::getProjectId, projectId)
                .eq(CustomField::getModule, module)
                .eq(CustomField::getViewType, viewType);
        Long total = customFieldMapper.selectCount(countWrapper);
        if (total == null || total != orderedIds.size()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "排序列表与该配置下的字段数量不一致");
        }

        // 按传入顺序重写 sortNo 为连续序号（仅更新 sortNo 有变化的行）
        Map<Long, CustomField> fieldMap = fields.stream()
                .collect(Collectors.toMap(CustomField::getId, f -> f));
        for (int i = 0; i < orderedIds.size(); i++) {
            CustomField item = fieldMap.get(orderedIds.get(i));
            int newSortNo = i + 1;
            if (item.getSortNo() == null || item.getSortNo() != newSortNo) {
                item.setSortNo(newSortNo);
                customFieldMapper.updateById(item);
            }
        }
    }

    /**
     * 字段类型校验
     */
    private void validateFieldType(CustomFieldCreateRequest request) {
        String type = request.getFieldType();
        if (!VALID_FIELD_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "不支持的字段类型：" + type);
        }
    }

    private CustomFieldListItem toListItem(CustomField field) {
        CustomFieldListItem item = new CustomFieldListItem();
        item.setId(field.getId());
        item.setProjectId(field.getProjectId());
        item.setModule(field.getModule());
        item.setViewType(field.getViewType());
        item.setFieldKey(field.getFieldKey());
        item.setFieldLabel(field.getFieldLabel());
        item.setDescription(field.getDescription());
        item.setFieldType(field.getFieldType());
        item.setOptionsJson(field.getOptionsJson());
        item.setDefaultValue(field.getDefaultValue());
        item.setIsRequired(field.getIsRequired());
        item.setSortNo(field.getSortNo());
        item.setIsActive(field.getIsActive());
        if (field.getCreatedAt() != null) {
            item.setCreatedAt(field.getCreatedAt().format(DT_FMT));
        }
        if (field.getUpdatedAt() != null) {
            item.setUpdatedAt(field.getUpdatedAt().format(DT_FMT));
        }
        return item;
    }

    private CustomFieldRenderDTO toRenderDTO(CustomField field) {
        CustomFieldRenderDTO dto = new CustomFieldRenderDTO();
        dto.setId(field.getId());
        dto.setFieldKey(field.getFieldKey());
        dto.setFieldLabel(field.getFieldLabel());
        dto.setFieldType(field.getFieldType());
        dto.setOptionsJson(field.getOptionsJson());
        dto.setDefaultValue(field.getDefaultValue());
        dto.setIsRequired(field.getIsRequired());
        dto.setSortNo(field.getSortNo());
        return dto;
    }
}
