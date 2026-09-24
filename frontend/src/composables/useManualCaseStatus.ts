/**
 * @description 手动用例状态选项加载器
 * 优先读【页面配置-手动用例字段】的"状态"字段配置（fieldKey=case_status，按项目），
 * 无配置时回退内置选项（使用/废弃），保证未配置项目功能不回退
 */
import { ref, computed, watch } from 'vue'
import { getCustomFieldsForRender } from '@/api/customField'

/** 内置回退选项（与后端 V71 预置的 case_status 选项一致，value 对齐 manual_case.case_status 列 1/0） */
const FALLBACK_OPTIONS = [
  { value: '1', label: '使用' },
  { value: '0', label: '废弃' },
]

export function useManualCaseStatusOptions(projectId: () => number | undefined) {
  // null = 未加载到配置（或项目无状态字段），此时回退内置选项
  const configOptions = ref<any[] | null>(null)

  watch(
    () => projectId(),
    async (pid) => {
      configOptions.value = null
      if (!pid) return
      try {
        const res: any = await getCustomFieldsForRender({ projectId: pid, module: 'manual_case', viewType: 'edit' })
        const field = (res.data || []).find((f: any) => f.fieldKey === 'case_status')
        if (field) {
          const opts =
            field.options && field.options.length > 0
              ? field.options
              : field.optionsJson
                ? JSON.parse(field.optionsJson)
                : []
          if (Array.isArray(opts) && opts.length > 0) configOptions.value = opts
        }
      } catch {
        // 读取失败保持回退选项
      }
    },
    { immediate: true },
  )

  const options = computed(() => configOptions.value ?? FALLBACK_OPTIONS)
  return { options }
}
