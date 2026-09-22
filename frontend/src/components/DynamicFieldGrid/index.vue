<!--
 @author HXN
 @date 2026-08-30
 @description 动态字段渲染组件
-->
<script setup lang="ts">
/**
 * 动态字段渲染网格
 *
 * 根据 fields 配置渲染对应的表单控件：
 * - text -> el-input（单行）
 * - textarea -> el-input type="textarea"（跨整行，3 行，最多 200 字符）
 * - select / user / environment -> el-select（选项统一来自后端组装的 field.options；
 *   select 兼容解析 optionsJson）
 * - datetime -> el-date-picker type="datetime"
 * - number -> el-input-number
 *
 * CSS Grid 3 列布局，自动排列
 * 支持 #prepend 插槽：插入的表单项（如“所属分组”）作为网格首格，
 * 与动态字段统一每行 3 个排列
 */

interface FieldOption {
  label: string
  value: string
}

interface FieldConfig {
  id: number
  fieldKey: string
  fieldLabel: string
  fieldType: string
  optionsJson?: string | null
  options?: FieldOption[] | null
  defaultValue?: string | null
  isRequired?: number
  sortNo?: number
}

const props = defineProps<{
  fields: FieldConfig[]
  modelValue: Record<string, any>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, any>]
  /** 字段值提交（el-input 失焦/回车、下拉选择、日期选择、数字变更时触发），供父组件即时保存 */
  'field-change': [fieldKey: string]
}>()

/** 下拉类字段类型（选项统一来自 field.options） */
const SELECT_TYPES = ['select', 'user', 'environment']

function isSelectField(field: FieldConfig): boolean {
  return SELECT_TYPES.includes(field.fieldType)
}

/** 解析下拉字段选项：优先后端组装的 options，回退解析 optionsJson（select 兼容） */
function parseOptions(field: FieldConfig): FieldOption[] {
  if (field.options && field.options.length > 0) return field.options
  if (!field.optionsJson) return []
  try {
    return JSON.parse(field.optionsJson)
  } catch {
    return []
  }
}

/** 更新字段值 */
function updateFieldValue(fieldKey: string, val: any) {
  const newVal = { ...props.modelValue, [fieldKey]: val }
  emit('update:modelValue', newVal)
}
</script>

<template>
  <div v-if="fields.length > 0" class="dynamic-field-grid">
    <!-- 前置插槽：父组件传入的表单项（如“所属分组”）参与 3 列网格统一排列 -->
    <slot name="prepend" />
    <el-form-item
      v-for="field in fields"
      :key="field.id"
      :label="field.fieldLabel"
      :required="field.isRequired === 1"
      :class="{ 'dfg-span-full': field.fieldType === 'textarea' }"
    >
      <!-- 单行文本 -->
      <el-input
        v-if="field.fieldType === 'text'"
        :model-value="modelValue[field.fieldKey] ?? ''"
        :placeholder="`请输入${field.fieldLabel}`"
        @update:model-value="updateFieldValue(field.fieldKey, $event)"
        @change="emit('field-change', field.fieldKey)"
      />

      <!-- 多行文本（跨整行，3 行；最多输入 200 个字符） -->
      <el-input
        v-else-if="field.fieldType === 'textarea'"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        :model-value="modelValue[field.fieldKey] ?? ''"
        :placeholder="`请输入${field.fieldLabel}`"
        @update:model-value="updateFieldValue(field.fieldKey, $event)"
        @change="emit('field-change', field.fieldKey)"
      />

      <!-- 下拉框（select 静态选项 / user 用户 / environment 环境） -->
      <el-select
        v-else-if="isSelectField(field)"
        :model-value="modelValue[field.fieldKey] ?? ''"
        :placeholder="`请选择${field.fieldLabel}`"
        clearable
        filterable
        style="width: 100%"
        @update:model-value="updateFieldValue(field.fieldKey, $event)"
        @change="emit('field-change', field.fieldKey)"
      >
        <el-option
          v-for="opt in parseOptions(field)"
          :key="opt.value"
          :value="opt.value"
          :label="opt.label"
        />
      </el-select>

      <!-- 日期时间 -->
      <el-date-picker
        v-else-if="field.fieldType === 'datetime'"
        :model-value="modelValue[field.fieldKey] ?? null"
        type="datetime"
        :placeholder="`选择${field.fieldLabel}`"
        value-format="YYYY-MM-DD HH:mm"
        style="width: 100%"
        @update:model-value="updateFieldValue(field.fieldKey, $event)"
        @change="emit('field-change', field.fieldKey)"
      />

      <!-- 数字 -->
      <el-input-number
        v-else-if="field.fieldType === 'number'"
        :model-value="modelValue[field.fieldKey] ?? null"
        :placeholder="`请输入${field.fieldLabel}`"
        :controls="false"
        style="width: 100%"
        @update:model-value="updateFieldValue(field.fieldKey, $event)"
        @change="emit('field-change', field.fieldKey)"
      />
    </el-form-item>
  </div>
</template>

<style scoped>
.dynamic-field-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0 16px;
}
/* 多行文本等长内容字段跨整行显示 */
.dfg-span-full {
  grid-column: 1 / -1;
}
</style>
