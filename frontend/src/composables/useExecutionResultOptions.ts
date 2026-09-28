/**
 * @description 执行结果选项加载器
 * 优先读【页面配置-用例执行】的"执行结果"字段配置（fieldKey=execution_result，按项目），
 * 无配置时回退内置选项（通过/失败/跳过），保证未配置项目标记功能不回退
 */
import { ref, computed, watch } from 'vue'
import { getCustomFieldsForRender } from '@/api/customField'

/** 内置回退选项（与后端 V81 预置的 execution_result 选项一致，value 对齐 round_results 现有编码） */
const FALLBACK_OPTIONS = [
  { value: 'PASSED', label: '通过' },
  { value: 'FAILED', label: '失败' },
  { value: 'SKIPPED', label: '跳过' },
]

export function useExecutionResultOptions(projectId: () => number | undefined) {
  // null = 未加载到配置（或项目无执行结果字段），此时回退内置选项
  const configOptions = ref<any[] | null>(null)

  watch(
    () => projectId(),
    async (pid) => {
      configOptions.value = null
      if (!pid) return
      try {
        const res: any = await getCustomFieldsForRender({ projectId: pid, module: 'execution', viewType: 'edit' })
        const field = (res.data || []).find((f: any) => f.fieldKey === 'execution_result')
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
