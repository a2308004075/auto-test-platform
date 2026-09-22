/**
 * 自定义字段"显示位置"（displayScope）工具
 *
 * 多值语义：create=新建显示 detail=详情(编辑)显示
 * （后端以逗号分隔字符串存储，接口以数组传输）
 */

/** 显示位置取值 */
export type FieldScope = 'create' | 'detail'

/**
 * 规范化"显示位置"值为列表：
 * - 数组原样取用；逗号分隔字符串按分隔解析（兼容后端旧字符串格式与切换期）
 * - 历史值 both / 空值缺省 → 都显示（create + detail）
 */
export function toScopeList(scope: unknown): string[] {
  const raw: string[] = Array.isArray(scope)
    ? scope.map((s) => String(s))
    : typeof scope === 'string'
      ? scope.split(',')
      : []
  const list = raw
    .map((s) => s.trim())
    .filter(Boolean)
    .flatMap((s) => (s === 'both' ? ['create', 'detail'] : [s]))
  return list.length > 0 ? list : ['create', 'detail']
}

/** 判断字段在指定位置是否显示 */
export function isScopeVisible(scope: unknown, place: FieldScope): boolean {
  return toScopeList(scope).includes(place)
}
