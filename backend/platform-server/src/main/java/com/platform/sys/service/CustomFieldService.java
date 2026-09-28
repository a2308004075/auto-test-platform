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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
     * 显示位置取值（新建在前、详情在后）：create=新建显示 detail=详情(编辑)显示
     */
    private static final List<String> ALL_DISPLAY_SCOPES = Arrays.asList("create", "detail");

    /**
     * 合法的显示位置集合
     */
    private static final Set<String> VALID_DISPLAY_SCOPES = new HashSet<>(ALL_DISPLAY_SCOPES);

    /**
     * 显示位置缺省存储值（都显示）：新建 + 详情
     */
    private static final String DEFAULT_DISPLAY_SCOPE = String.join(",", ALL_DISPLAY_SCOPES);

    /**
     * 缺陷状态字段的固定 fieldKey：缺陷列表/详情页流转状态下拉框的选项来源
     */
    public static final String DEFECT_STATUS_FIELD_KEY = "defect_status";

    /**
     * 用例执行模块标识：【页面配置-用例执行】功能页（执行页结果标记的选项来源）
     */
    public static final String EXECUTION_MODULE = "execution";

    /**
     * 执行结果字段的固定 fieldKey：手动计划执行页结果标记下拉框的选项来源
     */
    public static final String EXECUTION_RESULT_FIELD_KEY = "execution_result";

    /**
     * 各模块系统关键字段的固定 fieldKey 映射（module -> fieldKey）：
     * defect-状态（流转状态）、execution-执行结果（结果标记），
     * 均为系统预置、不可删除、固定排第一位
     */
    private static final Map<String, String> STATUS_FIELD_KEYS = new HashMap<>();
    static {
        STATUS_FIELD_KEYS.put("defect", DEFECT_STATUS_FIELD_KEY);
        STATUS_FIELD_KEYS.put(EXECUTION_MODULE, EXECUTION_RESULT_FIELD_KEY);
    }

    /**
     * "状态"字段固定显示位置：仅对应模块的详情显示（系统预置，不可修改）
     */
    private static final String STATUS_DISPLAY_SCOPE = "detail";

    /**
     * 新建项目时预置的状态选项（value 与 defect.status 现有英文编码一致，存量数据无需迁移）
     */
    private static final String DEFAULT_DEFECT_STATUS_OPTIONS_JSON =
            "[{\"label\":\"新建\",\"value\":\"NEW\"},{\"label\":\"待确认\",\"value\":\"TO_CONFIRM\"},{\"label\":\"修复中\",\"value\":\"FIXING\"},{\"label\":\"待部署\",\"value\":\"TO_DEPLOY\"},{\"label\":\"待验证\",\"value\":\"PENDING\"},{\"label\":\"已修复\",\"value\":\"COMPLETED\"},{\"label\":\"重新打开\",\"value\":\"REOPENED\"},{\"label\":\"延期修复\",\"value\":\"DEFERRED\"},{\"label\":\"无需修复\",\"value\":\"CLOSED\"}]";

    /**
     * 新建项目时预置的执行结果选项（value 与 test_result.round_results 现有英文编码一致，存量数据无需迁移）
     */
    private static final String DEFAULT_EXECUTION_RESULT_OPTIONS_JSON =
            "[{\"label\":\"通过\",\"value\":\"PASSED\"},{\"label\":\"失败\",\"value\":\"FAILED\"},{\"label\":\"跳过\",\"value\":\"SKIPPED\"}]";

    /**
     * 执行结果字段配置缺失/选项为空时的回退状态值（与预置选项一致，保证未配置项目标记功能不回退）
     */
    private static final List<String> FALLBACK_EXECUTION_RESULT_VALUES =
            Arrays.asList("PASSED", "FAILED", "SKIPPED");

    /**
     * 管理页列表（按 sortNo 排序；"状态"系统字段固定排第一位）
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
        // "状态"字段位置不可修改：展示时固定提到第一位（其余字段保持原相对顺序）
        List<CustomField> ordered = new ArrayList<>();
        fields.stream().filter(f -> isStatusField(f.getModule(), f.getFieldKey())).forEach(ordered::add);
        fields.stream().filter(f -> !isStatusField(f.getModule(), f.getFieldKey())).forEach(ordered::add);
        return ordered.stream().map(this::toListItem).collect(Collectors.toList());
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
        validateSelectOptionsRequired(request);
        validateFieldLabelUnique(request, null);

        CustomField field = new CustomField();
        BeanUtils.copyProperties(request, field);
        field.setFieldKey("field_" + UUID.randomUUID().toString().replace("-", ""));
        // displayScope 请求为列表、实体为逗号分隔字符串，类型不匹配不会被 copyProperties 复制，需手动转换
        field.setDisplayScope(joinDisplayScopes(request.getDisplayScope()));
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
        validateSelectOptionsRequired(request);

        CustomField field = customFieldMapper.selectById(id);
        if (field == null) {
            throw new BusinessException(ErrorCode.CUSTOM_FIELD_NOT_FOUND, "字段不存在");
        }
        validateFieldLabelUnique(request, id);

        // 排序值仅由拖拽排序接口（sort）专职管理：忽略请求携带的值，防止旧页面用陈旧值覆盖
        Integer originalSortNo = field.getSortNo();
        BeanUtils.copyProperties(request, field);
        field.setSortNo(originalSortNo);
        // displayScope 需手动转换（类型不匹配不会被复制）；请求缺省（null/空）时保持原值不动
        List<String> scopes = request.getDisplayScope();
        if (scopes != null && !scopes.isEmpty()) {
            field.setDisplayScope(joinDisplayScopes(scopes));
        }
        // "状态"系统字段：必填固定为"是"、显示位置固定为对应模块的详情，不可被修改
        // （请求值一律忽略，置于 displayScope 转换之后确保覆盖请求值）
        if (isStatusField(field.getModule(), field.getFieldKey())) {
            field.setIsRequired(1);
            field.setDisplayScope(STATUS_DISPLAY_SCOPE);
        }
        customFieldMapper.updateById(field);
        return toListItem(field);
    }

    /**
     * 为项目预置缺陷"状态"字段（【页面配置-缺陷字段】视图）
     *
     * <p>新建项目时调用：状态字段是流转状态下拉框的选项来源，须始终可配置；
     * 选项 value 沿用 defect.status 现有英文编码，存量数据无需迁移
     */
    @Transactional(rollbackFor = Exception.class)
    public void createDefaultStatusField(Long projectId) {
        createStatusField(projectId, "defect", DEFECT_STATUS_FIELD_KEY,
                "缺陷流转状态下拉框的枚举选项：可增删选项、修改显示名、调整顺序；删除选项后存量缺陷保留原状态值",
                DEFAULT_DEFECT_STATUS_OPTIONS_JSON);
    }

    /**
     * 为项目预置"执行结果"字段（【页面配置-用例执行】功能页）
     *
     * <p>新建项目时调用：执行结果字段是手动计划执行页结果标记下拉框的选项来源，须始终可配置；
     * 选项 value 沿用 test_result.round_results 现有英文编码（PASSED/FAILED/SKIPPED），存量数据无需迁移；
     * 结果值不走 sys_custom_field_value（存 test_result.round_results JSON），配置仅作选项来源
     */
    @Transactional(rollbackFor = Exception.class)
    public void createDefaultExecutionResultField(Long projectId) {
        createStatusField(projectId, EXECUTION_MODULE, EXECUTION_RESULT_FIELD_KEY,
                "手动计划执行页结果标记下拉框的枚举选项：可增删选项、修改显示名、调整顺序；删除选项后已记录的结果保留原值",
                DEFAULT_EXECUTION_RESULT_OPTIONS_JSON);
    }

    /**
     * 读取项目"执行结果"字段配置的合法状态值列表（执行页单元格标记校验用）
     *
     * <p>配置缺失/选项为空/解析失败时回退默认 PASSED/FAILED/SKIPPED，保证未配置项目标记功能不回退
     */
    public List<String> listExecutionResultStatusValues(Long projectId) {
        if (projectId == null) {
            return FALLBACK_EXECUTION_RESULT_VALUES;
        }
        LambdaQueryWrapper<CustomField> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomField::getProjectId, projectId)
                .eq(CustomField::getModule, EXECUTION_MODULE)
                .eq(CustomField::getFieldKey, EXECUTION_RESULT_FIELD_KEY)
                .eq(CustomField::getIsActive, 1);
        List<CustomField> fields = customFieldMapper.selectList(wrapper);
        if (fields.isEmpty()) {
            return FALLBACK_EXECUTION_RESULT_VALUES;
        }
        String optionsJson = fields.get(0).getOptionsJson();
        if (optionsJson == null || optionsJson.trim().isEmpty()) {
            return FALLBACK_EXECUTION_RESULT_VALUES;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, String>> rows = mapper.readValue(optionsJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            List<String> values = new ArrayList<>();
            for (Map<String, String> row : rows) {
                String value = row.get("value");
                if (value != null && !value.trim().isEmpty()) {
                    values.add(value.trim());
                }
            }
            return values.isEmpty() ? FALLBACK_EXECUTION_RESULT_VALUES : values;
        } catch (Exception e) {
            log.warn("解析执行结果字段选项 JSON 失败: {}", optionsJson, e);
            return FALLBACK_EXECUTION_RESULT_VALUES;
        }
    }

    /**
     * 预置模块"状态"字段（统一存 edit 视图）
     *
     * <p>状态字段系统预置为必填、显示位置固定为"详情"，且固定排第一位（列表展示与拖拽均锁定位置）
     */
    private void createStatusField(Long projectId, String module, String fieldKey,
                                   String description, String optionsJson) {
        CustomField field = new CustomField();
        field.setProjectId(projectId);
        field.setModule(module);
        field.setViewType("edit");
        field.setFieldKey(fieldKey);
        field.setFieldLabel("状态");
        field.setDescription(description);
        field.setFieldType("select");
        field.setOptionsJson(optionsJson);
        field.setIsRequired(1);
        field.setDisplayScope(STATUS_DISPLAY_SCOPE);
        field.setSortNo(1);
        field.setIsActive(1);
        customFieldMapper.insert(field);
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
        // 系统关键字段（缺陷状态/执行结果）是下拉框的选项来源，且新建字段的 fieldKey 为自动生成的 UUID，
        // 删除后无法重建同 fieldKey 的字段，故禁止删除
        if (isStatusField(field.getModule(), field.getFieldKey())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "系统预置的「" + field.getFieldLabel() + "」字段不可删除");
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

        // 系统关键字段（缺陷状态/执行结果）位置不可修改：配置中存在时，其必须位于提交顺序的第一位
        boolean hasStatusField = fields.stream()
                .anyMatch(f -> isStatusField(f.getModule(), f.getFieldKey()));
        if (hasStatusField && !isStatusField(module, fieldMap.get(orderedIds.get(0)).getFieldKey())) {
            String statusFieldLabel = fields.stream()
                    .filter(f -> isStatusField(f.getModule(), f.getFieldKey()))
                    .findFirst().map(CustomField::getFieldLabel).orElse("状态");
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "「" + statusFieldLabel + "」字段固定排第一位，不能调整其位置");
        }

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
     * 判断是否为系统预置的"状态"字段（fieldKey 与模块约定的状态字段一致）
     */
    private boolean isStatusField(String module, String fieldKey) {
        return module != null && fieldKey != null && fieldKey.equals(STATUS_FIELD_KEYS.get(module));
    }

    /**
     * 字段类型与显示位置校验
     */
    private void validateFieldType(CustomFieldCreateRequest request) {
        String type = request.getFieldType();
        if (!VALID_FIELD_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "不支持的字段类型：" + type);
        }
        List<String> scopes = request.getDisplayScope();
        if (scopes != null) {
            for (String scope : scopes) {
                if (!VALID_DISPLAY_SCOPES.contains(scope)) {
                    throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                            "不支持的显示位置：" + scope);
                }
            }
        }
    }

    /**
     * 「下拉框」类型字段的枚举选项必填校验（至少一项）
     *
     * <p>前端弹窗已拦截，此处兜底防止绕过接口提交空选项；
     * optionsJson 结构为 [{"label":"显示文本","value":"存储值"},...]
     */
    private void validateSelectOptionsRequired(CustomFieldCreateRequest request) {
        if (!"select".equals(request.getFieldType())) {
            return;
        }
        String optionsJson = request.getOptionsJson();
        if (optionsJson == null || optionsJson.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "请至少添加一项枚举选项");
        }
        List<Map<String, String>> rows;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            rows = mapper.readValue(optionsJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "枚举选项数据格式不合法");
        }
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "请至少添加一项枚举选项");
        }
    }

    /**
     * 字段标签唯一性校验：同一配置（项目 + 模块 + 视图）内不可重复
     *
     * <p>编辑时排除自身（excludeId）；已删除字段（@TableLogic）不参与校验，
     * 其标签可被新字段复用；比较前去除首尾空格，库表排序规则为大小写不敏感（utf8mb4_unicode_ci）
     *
     * @param excludeId 需排除的字段 ID（编辑场景传当前字段 ID，新建场景传 null）
     */
    private void validateFieldLabelUnique(CustomFieldCreateRequest request, Long excludeId) {
        String label = request.getFieldLabel() == null ? "" : request.getFieldLabel().trim();
        if (label.isEmpty()) {
            // 非空由 DTO 的 @NotBlank 保证，此处仅兜底
            return;
        }
        LambdaQueryWrapper<CustomField> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomField::getProjectId, request.getProjectId())
                .eq(CustomField::getModule, request.getModule())
                .eq(CustomField::getViewType, request.getViewType())
                .eq(CustomField::getFieldLabel, label);
        if (excludeId != null) {
            wrapper.ne(CustomField::getId, excludeId);
        }
        Long count = customFieldMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.CUSTOM_FIELD_LABEL_DUPLICATE,
                    "字段标签「" + label + "」已存在");
        }
    }

    /**
     * 显示位置列表 → 逗号分隔存储值（固定 create,detail 顺序；缺省或空集视为"都显示"）
     */
    private String joinDisplayScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return DEFAULT_DISPLAY_SCOPE;
        }
        String joined = ALL_DISPLAY_SCOPES.stream()
                .filter(scopes::contains)
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? DEFAULT_DISPLAY_SCOPE : joined;
    }

    /**
     * 逗号分隔存储值 → 显示位置列表（历史值 both 与空值兜底为"都显示"）
     */
    private List<String> splitDisplayScopes(String raw) {
        if (raw == null || raw.isEmpty() || "both".equals(raw)) {
            return new ArrayList<>(ALL_DISPLAY_SCOPES);
        }
        List<String> scopes = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .collect(Collectors.toList());
        return scopes.isEmpty() ? new ArrayList<>(ALL_DISPLAY_SCOPES) : scopes;
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
        item.setDisplayScope(splitDisplayScopes(field.getDisplayScope()));
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
        dto.setDisplayScope(splitDisplayScopes(field.getDisplayScope()));
        dto.setSortNo(field.getSortNo());
        return dto;
    }
}
