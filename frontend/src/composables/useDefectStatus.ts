/**
 * @description 缺陷状态选项加载器
 * 优先读【字段管理-缺陷字段】的"状态"字段配置（fieldKey=defect_status，按项目），
 * 无配置时回退 defect_status 字典（9 个默认状态），保证未配置项目功能不回退
 */
import { ref, computed, watch } from 'vue'
import { useDict } from './useDict'
import { getCustomFieldsForRender } from '@/api/customField'

export function useDefectStatusOptions(projectId: () => number | undefined) {
  const { options: dictOptions } = useDict('defect_status')
  // null = 未加载到配置（或项目无状态字段），此时回退字典
  const configOptions = ref<any[] | null>(null)

  watch(
    () => projectId(),
    async (pid) => {
      configOptions.value = null
      if (!pid) return
      try {
        const res: any = await getCustomFieldsForRender({ projectId: pid, module: 'defect', viewType: 'edit' })
        const field = (res.data || []).find((f: any) => f.fieldKey === 'defect_status')
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
        // 读取失败保持回退字典
      }
    },
    { immediate: true },
  )

  const options = computed(() => configOptions.value ?? dictOptions.value)
  return { options }
}
