import {defineStore} from 'pinia'
import {ref} from 'vue'
import {TaskStatus, type ExtractionResult, type TaskProgress} from '@/types/task'

/**
 * 异步解析任务状态管理
 * - 保存当前任务进度，便于 AI 填报页面刷新 / 重新进入时恢复状态
 * - SSE 监听逻辑在 Phase 5 实现（utils/sse.ts），此处仅维护状态
 */
export const useTaskStore = defineStore('task', () => {
    // 当前任务 ID
    const currentTaskId = ref<string>('')
    // 关联文件 ID
    const fileId = ref<number>(0)
    // 关联表单 ID
    const formId = ref<number>(0)
    // 任务状态
    const status = ref<TaskStatus | ''>('')
    // 进度百分比
    const percent = ref<number>(0)
    // 阶段消息
    const message = ref<string>('')
    // AI 提取结果
    const result = ref<ExtractionResult | null>(null)
    // 是否解析中
    const loading = ref<boolean>(false)

    /** 初始化任务基础信息（启动任务时调用） */
    function setTask(taskId: string, fid: number, fmid: number) {
        currentTaskId.value = taskId
        fileId.value = fid
        formId.value = fmid
        status.value = ''
        percent.value = 0
        message.value = ''
        result.value = null
        loading.value = true
    }

    /** 根据 SSE 推送更新进度 */
    function updateProgress(progress: TaskProgress) {
        status.value = progress.status
        percent.value = progress.percent
        message.value = progress.message
        if (progress.result) {
            result.value = progress.result
        }
    }

    /** 标记任务结束（成功或失败） */
    function finish() {
        loading.value = false
    }

    /** 重置全部状态 */
    function reset() {
        currentTaskId.value = ''
        fileId.value = 0
        formId.value = 0
        status.value = ''
        percent.value = 0
        message.value = ''
        result.value = null
        loading.value = false
    }

    return {
        currentTaskId,
        fileId,
        formId,
        status,
        percent,
        message,
        result,
        loading,
        setTask,
        updateProgress,
        finish,
        reset
    }
})
