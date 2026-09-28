/**
 * @author HXN
 * @date 2026-09-28
 * @description 自定义字段默认值应用工具
 */

/**
 * 自定义字段默认值应用工具
 *
 * 新建表单初始化时把【页面配置-字段设置】配置的默认值填入字段值对象；
 * datetime 类型支持哨兵 NOW（配置页勾选"新建时取当前时间"时存储），应用时翻译为当前时刻
 */

/** datetime 默认值哨兵：新建表单初始化时翻译为当前时间（与配置页 DATETIME_NOW_DEFAULT 同源约定） */
const NOW_SENTINEL = 'NOW'

/** datetime 哨兵值（配置页勾选"新建时取当前时间"时写入 default_value 存储） */
export const DATETIME_NOW_DEFAULT = NOW_SENTINEL

/**
 * 应用字段配置默认值到表单值对象（仅填充未定义的键，不覆盖已有值）
 *
 * @param fields 字段配置列表（含 defaultValue/fieldKey/fieldType）
 * @param values 当前表单值（fieldKey -> 值）；返回新对象，不修改入参
 */
export function applyFieldDefaults(
  fields: any[],
  values: Record<string, any>,
): Record<string, any> {
  const result = { ...values }
  for (const field of fields) {
    const dv = field?.defaultValue
    if (dv === null || dv === undefined || dv === '') continue
    if (result[field.fieldKey] !== undefined) continue
    // datetime 哨兵 NOW 翻译为当前时刻（格式与 DynamicFieldGrid 的 value-format 一致）
    result[field.fieldKey] =
      field.fieldType === 'datetime' && dv === NOW_SENTINEL ? formatNow() : dv
  }
  return result
}

/** 当前时刻（yyyy-MM-dd HH:mm，与动态字段日期时间控件 value-format 一致） */
function formatNow(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
