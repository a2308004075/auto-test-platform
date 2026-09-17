/**
 * @author HXN
 * @date 2026-08-18 17:31
 * @description 项目状态 Store
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

/** localStorage 持久化 key（当前项目跨刷新保留） */
const STORAGE_KEY = 'current-project'

/** 从 localStorage 恢复当前项目（解析失败返回空值） */
function restoreFromStorage(): { id: number; name: string } {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (parsed && typeof parsed.id === 'number' && typeof parsed.name === 'string') {
        return { id: parsed.id, name: parsed.name }
      }
    }
  } catch {
    // ignore
  }
  return { id: 0, name: '' }
}

/**
 * 项目状态管理
 * 当前项目持久化到 localStorage，浏览器刷新后不丢失（退出登录时清除）
 */
export const useProjectStore = defineStore('project', () => {
  const restored = restoreFromStorage()
  const currentProjectId = ref<number>(restored.id)
  const currentProjectName = ref<string>(restored.name)

  function setCurrentProject(id: number, name: string) {
    currentProjectId.value = id
    currentProjectName.value = name
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ id, name }))
  }

  function clearCurrentProject() {
    currentProjectId.value = 0
    currentProjectName.value = ''
    localStorage.removeItem(STORAGE_KEY)
  }

  return {
    currentProjectId,
    currentProjectName,
    setCurrentProject,
    clearCurrentProject,
  }
})
