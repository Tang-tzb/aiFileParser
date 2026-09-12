<script lang="ts" setup>
import {computed, onMounted, onUnmounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage} from 'element-plus'
import {getFormDetail} from '@/api/form'
import {getProjectFiles, getProjectForms, getProjectPage} from '@/api/project'
import {startTask} from '@/api/task'
import {subscribeTaskProgress} from '@/utils/sse'
import {getLastUpload} from '@/utils/lastUpload'
import {useTaskStore} from '@/stores/task'
import {type FieldError, TASK_STATUS_LABELS, type TaskProgress, type TaskStartVO, TaskStatus} from '@/types/task'
import type {FormFieldVO} from '@/types/form'
import type {FileRecordVO} from '@/types/file'
import {ProjectFormStatus, type ProjectFormVO, type ProjectVO} from '@/types/project'

/**
 * AI 自动填报页（Phase 4 / Phase C 项目上下文）
 *
 * - 配置区：选择项目 → 选择表单（项目绑定实例）→ 选择文件（项目内文件）→ 启动异步任务（POST /task）
 * - 项目维度强约束：表单/文件下拉均来自所选项目，保证"表单/文件/项目"三者关联一致（6011 前置规避）
 * - lastUpload 防串项目：仅当最近上传记录归属与所选项目一致时才预填文件
 * - 进度区：SSE 订阅 /task/progress/{taskId}，展示进度条 / 阶段状态 / 消息
 * - 结果区：抽取值表 + 错误列表 + 重试次数
 * - 离开页面 onUnmounted 关闭 EventSource，避免连接泄漏
 * - 终态（SUCCESS/FAILED）自动关闭连接，详见 utils/sse.ts
 */
defineOptions({name: 'AiFill'})

const route = useRoute()
const taskStore = useTaskStore()

// ===== 配置区状态 =====
// 项目下拉数据
const projectOptions = ref<ProjectVO[]>([])
// 表单下拉数据（项目绑定的 ACTIVE 实例）
const formOptions = ref<ProjectFormVO[]>([])
// 文件下拉数据（项目内文件）
const fileOptions = ref<FileRecordVO[]>([])
// 选中的项目 ID（字符串以兼容大整数）
const selectedProjectId = ref<string>('')
// 选中的表单 ID（字符串以兼容大整数）
const selectedFormId = ref<string>('')
// 选中的文件 ID（字符串以兼容大整数）
const selectedFileId = ref<string>('')
// 当前选中表单的字段列表（用于预览 + 结果字段映射）
const formFields = ref<FormFieldVO[]>([])
// 下拉加载中
const projectLoading = ref(false)
const formLoading = ref(false)
const fileLoading = ref(false)
// 字段详情加载中
const fieldsLoading = ref(false)
// 启动任务中
const starting = ref(false)

// ===== 进度区状态 =====
// SSE EventSource 实例引用，供卸载/重置时主动 close
let eventSource: EventSource | null = null
// SSE 连接是否已建立
const connected = ref(false)
// 错误提示（连接异常或任务失败）
const errorMsg = ref('')

// ===== 计算属性 =====
// 是否任务进行中（解析/向量化/AI抽取）
const running = computed(() =>
    taskStore.loading
    && taskStore.status !== TaskStatus.SUCCESS
    && taskStore.status !== TaskStatus.FAILED
)
// 是否终态（用于禁用/启用启动按钮）
const finished = computed(() =>
    taskStore.status === TaskStatus.SUCCESS
    || taskStore.status === TaskStatus.FAILED
)
// 当前阶段对应的进度条状态
const progressStatus = computed<'success' | 'exception' | undefined>(() => {
  if (taskStore.status === TaskStatus.SUCCESS) return 'success'
  if (taskStore.status === TaskStatus.FAILED) return 'exception'
  return undefined
})
/**
 * 当前阶段对应的步骤索引
 * - 解析中 → 0
 * - 向量化中 → 1
 * - AI 抽取中 → 2
 * - 完成 → 4（全部完成）
 * - 失败 → 保持当前阶段
 */
const stageStep = computed(() => {
  switch (taskStore.status) {
    case TaskStatus.PARSING:
      return 0
    case TaskStatus.VECTORING:
      return 1
    case TaskStatus.EXTRACTING:
      return 2
    case TaskStatus.SUCCESS:
      return 4
    default:
      return 0
  }
})
// 提取结果展示数据（将 values 映射到字段名）
const valueRows = computed(() => {
  const res = taskStore.result
  if (!res?.values) return []
  return formFields.value.map(f => ({
    fieldName: f.fieldName,
    fieldCode: f.fieldCode,
    fieldType: f.fieldType,
    required: f.required,
    value: res.values[f.fieldCode]
  }))
})
// 错误列表
const errorRows = computed<FieldError[]>(() => taskStore.result?.errors ?? [])

// ===== 加载下拉数据 =====
/** 加载项目下拉列表（仅取第一页 50 条，覆盖大多数场景） */
async function loadProjectOptions() {
  projectLoading.value = true
  try {
    const data = await getProjectPage({pageNum: 1, pageSize: 50})
    projectOptions.value = data?.records ?? []
  } catch {
    // 错误由 axios 拦截器统一提示
  } finally {
    projectLoading.value = false
  }
}

/** 加载表单下拉（所选项目绑定的 ACTIVE 实例，保证表单/文件/项目三者一致） */
async function loadFormOptions() {
  if (!selectedProjectId.value) {
    formOptions.value = []
    return
  }
  formLoading.value = true
  try {
    const data = await getProjectForms(selectedProjectId.value, {pageNum: 1, pageSize: 50})
    formOptions.value = (data?.records ?? []).filter(
        (f) => f.status === ProjectFormStatus.ACTIVE
    )
  } catch {
    // 错误由 axios 拦截器统一提示
  } finally {
    formLoading.value = false
  }
}

/** 加载文件下拉（仅所选项目内的文件） */
async function loadFileOptions() {
  if (!selectedProjectId.value) {
    fileOptions.value = []
    return
  }
  fileLoading.value = true
  try {
    const data = await getProjectFiles(selectedProjectId.value, {pageNum: 1, pageSize: 50})
    fileOptions.value = data?.records ?? []
  } catch {
    // 错误由 axios 拦截器统一提示
  } finally {
    fileLoading.value = false
  }
}

// ===== 选择联动 =====
/** 选择项目后：清空表单/文件选择并重拉项目维度下拉 */
async function handleProjectChange() {
  selectedFormId.value = ''
  selectedFileId.value = ''
  formFields.value = []
  await Promise.all([loadFormOptions(), loadFileOptions()])
}

/** 选择表单后，加载其字段列表用于预览 */
async function handleFormChange(formId: string) {
  formFields.value = []
  if (!formId) return
  fieldsLoading.value = true
  try {
    const detail = await getFormDetail(formId)
    formFields.value = detail?.fields ?? []
  } catch {
    // 错误由 axios 拦截器统一提示
  } finally {
    fieldsLoading.value = false
  }
}

/** 获取选中文件的对象（用于展示文件类型/状态） */
function findFile(fileId: string): FileRecordVO | undefined {
  return fileOptions.value.find(f => String(f.fileId) === fileId)
}

// ===== 启动任务 + SSE 订阅 =====
/** 校验并启动异步任务，启动成功后立即订阅 SSE */
async function handleStart() {
  errorMsg.value = ''
  if (!selectedProjectId.value) {
    ElMessage.warning('请选择项目')
    return
  }
  if (!selectedFormId.value) {
    ElMessage.warning('请选择表单')
    return
  }
  if (!selectedFileId.value) {
    ElMessage.warning('请选择文件')
    return
  }
  // 关闭旧连接（重新启动场景）
  closeSse()
  taskStore.reset()

  starting.value = true
  let startVO: TaskStartVO
  try {
    startVO = await startTask({
      formId: selectedFormId.value,
      fileId: selectedFileId.value,
      projectId: selectedProjectId.value
    })
  } catch {
    // 错误由 axios 拦截器统一提示
    starting.value = false
    return
  }
  starting.value = false

  // 初始化任务状态
  taskStore.setTask(startVO.taskId, startVO.fileId, startVO.formId)
  // 订阅进度
  eventSource = subscribeTaskProgress(startVO.taskId, {
    onOpen: () => {
      connected.value = true
    },
    onProgress: handleProgress,
    onError: handleSseError
  })
}

/** 处理一帧 SSE 进度 */
function handleProgress(p: TaskProgress) {
  taskStore.updateProgress(p)
  // 终态时关闭连接并提示
  if (p.status === TaskStatus.SUCCESS) {
    taskStore.finish()
    ElMessage.success('解析完成')
  } else if (p.status === TaskStatus.FAILED) {
    taskStore.finish()
    errorMsg.value = p.message || '任务处理失败'
    ElMessage.error(errorMsg.value)
  }
}

/** SSE 连接异常处理（已自动 close） */
function handleSseError(_err: Event) {
  connected.value = false
  // 仅在非终态时提示连接异常（避免与 FAILED 重复提示）
  if (!finished.value) {
    errorMsg.value = 'SSE 连接异常，进度推送中断'
    taskStore.finish()
    ElMessage.error(errorMsg.value)
  }
}

/** 关闭 SSE 连接（重置/卸载/重启时调用） */
function closeSse() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  connected.value = false
}

// ===== 重置 =====
/** 重置全部状态，可重新选择启动 */
function handleReset() {
  closeSse()
  taskStore.reset()
  errorMsg.value = ''
}

/** 格式化抽取值（对象/数组序列化，其他原样展示） */
function formatValue(v: unknown): string {
  if (v === null || v === undefined) return ''
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

// ===== 生命周期 =====
onMounted(async () => {
  await loadProjectOptions()
  const last = getLastUpload()
  // 项目初始化优先级：query（从上传页跳转延续）> lastUpload 归属 > 空
  const queryProjectId = (route.query.projectId as string) || ''
  if (queryProjectId && projectOptions.value.some(p => String(p.projectId) === queryProjectId)) {
    selectedProjectId.value = queryProjectId
  } else if (last?.projectId && projectOptions.value.some(p => String(p.projectId) === last.projectId)) {
    selectedProjectId.value = last.projectId
  }
  // 项目确定后加载项目维度下拉
  if (selectedProjectId.value) {
    await Promise.all([loadFormOptions(), loadFileOptions()])
    // 防串项目预填：仅当最近上传记录归属与所选项目一致且文件在选项中时预填
    if (last?.fileId && last.projectId && String(last.projectId) === String(selectedProjectId.value)
        && fileOptions.value.some(f => String(f.fileId) === last.fileId)) {
      selectedFileId.value = last.fileId
    }
  }
})

onUnmounted(() => {
  // 离开页面主动关闭 SSE，避免连接泄漏
  closeSse()
})
</script>

<template>
  <div class="ai-fill-page">
    <!-- 标题 -->
    <el-card class="header-card" shadow="never">
      <div class="header">
        <h2 class="title">AI 自动填报</h2>
        <p class="desc">选择项目、表单与文件后启动异步解析，SSE 实时推送进度，完成后展示 AI 抽取结果</p>
      </div>
    </el-card>

    <!-- 配置区 -->
    <el-card class="config-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>
            <el-icon><Setting/></el-icon>
            任务配置
          </span>
        </div>
      </template>

      <el-form label-position="right" label-width="80px">
        <el-form-item label="选择项目">
          <el-select
              v-model="selectedProjectId"
              :disabled="running"
              :loading="projectLoading"
              class="full-width"
              filterable
              placeholder="请选择项目"
              @change="handleProjectChange"
          >
            <el-option
                v-for="p in projectOptions"
                :key="p.projectId"
                :label="`${p.projectName}（${p.projectNo}）`"
                :value="String(p.projectId)"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="选择表单">
          <el-select
              v-model="selectedFormId"
              :disabled="!selectedProjectId || running"
              :loading="formLoading"
              class="full-width"
              filterable
              :placeholder="selectedProjectId ? '请选择表单' : '请先选择项目'"
              @change="handleFormChange"
          >
            <el-option
                v-for="f in formOptions"
                :key="f.projectFormId"
                :label="`${f.formName}（${f.formId}）`"
                :value="String(f.formId)"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="选择文件">
          <el-select
              v-model="selectedFileId"
              :disabled="!selectedProjectId || running"
              :loading="fileLoading"
              class="full-width"
              filterable
              :placeholder="selectedProjectId ? '请选择文件' : '请先选择项目'"
          >
            <el-option
                v-for="f in fileOptions"
                :key="f.fileId"
                :label="`${f.fileName}（${f.fileId}）`"
                :value="String(f.fileId)"
            />
          </el-select>
        </el-form-item>

        <el-form-item v-if="findFile(selectedFileId)" label="文件信息">
          <el-tag size="small" type="info">
            {{ findFile(selectedFileId)?.fileName }}
          </el-tag>
        </el-form-item>

        <!-- 启动按钮 -->
        <el-form-item>
          <el-button
              :disabled="!selectedProjectId || !selectedFormId || !selectedFileId || running"
              :loading="starting"
              size="large"
              type="primary"
              @click="handleStart"
          >
            <el-icon>
              <MagicStick/>
            </el-icon>
            {{ running ? '任务进行中' : (finished ? '重新启动' : '开始解析') }}
          </el-button>
          <el-button v-if="finished || errorMsg" size="large" @click="handleReset">
            重置
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 表单字段预览 -->
      <div v-if="formFields.length" class="fields-preview">
        <div class="preview-title">
          <el-icon>
            <Document/>
          </el-icon>
          表单字段预览（共 {{ formFields.length }} 个）
        </div>
        <el-tag
            v-for="f in formFields"
            :key="f.fieldId"
            :type="f.required ? 'danger' : 'info'"
            class="field-tag"
            size="small"
        >
          {{ f.fieldName }}
        </el-tag>
      </div>
      <div v-else-if="fieldsLoading" class="preview-empty">字段加载中...</div>
    </el-card>

    <!-- 进度区 -->
    <el-card v-if="taskStore.currentTaskId" class="progress-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>
            <el-icon><DataLine/></el-icon>
            解析进度
          </span>
          <el-tag v-if="connected" size="small" type="success">SSE 已连接</el-tag>
          <el-tag v-else-if="running" size="small" type="warning">连接中</el-tag>
        </div>
      </template>

      <!-- 进度条 -->
      <el-progress
          :percentage="taskStore.percent >= 0 ? taskStore.percent : 0"
          :status="progressStatus"
          :stroke-width="14"
          :text-inside="true"
      />

      <!-- 阶段状态 + 消息 -->
      <div class="status-bar">
        <el-tag v-if="taskStore.status" :type="progressStatus === 'exception' ? 'danger' : 'primary'">
          {{ TASK_STATUS_LABELS[taskStore.status] || taskStore.status }}
        </el-tag>
        <span v-if="taskStore.message" class="status-msg">{{ taskStore.message }}</span>
      </div>

      <!-- 阶段时间线 -->
      <el-steps :active="stageStep" align-center class="stage-steps">
        <el-step title="解析中"/>
        <el-step title="向量化中"/>
        <el-step title="AI 抽取中"/>
        <el-step title="完成"/>
      </el-steps>

      <!-- 错误提示 -->
      <el-alert
          v-if="errorMsg"
          :closable="false"
          class="error-alert"
          show-icon
          title="处理失败"
          type="error"
      >
        {{ errorMsg }}
      </el-alert>
    </el-card>

    <!-- 结果区 -->
    <el-card v-if="taskStore.result" class="result-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>
            <el-icon color="#67c23a"><CircleCheck/></el-icon>
            AI 抽取结果
          </span>
          <el-tag size="small">重试次数：{{ taskStore.result.attemptsUsed }}</el-tag>
        </div>
      </template>

      <el-table :data="valueRows" border stripe>
        <el-table-column label="字段名称" min-width="140" prop="fieldName"/>
        <el-table-column label="字段编码" min-width="140" prop="fieldCode"/>
        <el-table-column label="是否必填" width="100">
          <template #default="{ row }">
            <el-tag :type="row.required ? 'danger' : 'info'" size="small">
              {{ row.required ? '必填' : '可选' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="抽取值" min-width="200">
          <template #default="{ row }">
            <span v-if="row.value === null || row.value === undefined" class="empty">（未抽取到）</span>
            <span v-else class="mono">{{ formatValue(row.value) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 错误列表 -->
      <div v-if="errorRows.length" class="error-list">
        <div class="error-title">
          <el-icon color="#f56c6c">
            <Warning/>
          </el-icon>
          字段抽取错误（{{ errorRows.length }} 项）
        </div>
        <el-table :data="errorRows" border stripe>
          <el-table-column label="字段编码" min-width="140" prop="fieldCode"/>
          <el-table-column label="错误类型" min-width="120" prop="errorType"/>
          <el-table-column label="错误信息" min-width="200" prop="message" show-overflow-tooltip/>
          <el-table-column label="原始值" min-width="200">
            <template #default="{ row }">
              <span class="mono">{{ formatValue(row.rawValue) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.ai-fill-page {
  max-width: 1100px;
  margin: 0 auto;

  .header-card {
    margin-bottom: 16px;
    text-align: center;

    .header {
      .title {
        margin: 0 0 8px 0;
        font-size: 22px;
        font-weight: 600;
        color: #303133;
      }

      .desc {
        margin: 0;
        font-size: 13px;
        color: #909399;
      }
    }
  }

  .config-card {
    margin-bottom: 16px;

    .card-header {
      display: flex;
      align-items: center;
      gap: 6px;
      font-weight: 600;
    }

    .full-width {
      width: 100%;
    }

    .fields-preview {
      margin-top: 16px;
      padding: 12px 16px;
      background-color: #f5f7fa;
      border-radius: 4px;

      .preview-title {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        color: #606266;
        margin-bottom: 10px;
      }

      .field-tag {
        margin: 4px 6px 4px 0;
      }
    }

    .preview-empty {
      margin-top: 12px;
      color: #909399;
      font-size: 13px;
    }
  }

  .progress-card {
    margin-bottom: 16px;

    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-weight: 600;

      span {
        display: flex;
        align-items: center;
        gap: 6px;
      }
    }

    .status-bar {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-top: 16px;

      .status-msg {
        font-size: 13px;
        color: #606266;
      }
    }

    .stage-steps {
      margin-top: 24px;
    }

    .error-alert {
      margin-top: 16px;
    }
  }

  .result-card {
    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-weight: 600;

      span {
        display: flex;
        align-items: center;
        gap: 6px;
      }
    }

    .mono {
      font-family: 'Consolas', 'Monaco', monospace;
      color: #303133;
      word-break: break-all;
    }

    .empty {
      color: #c0c4cc;
      font-style: italic;
    }

    .error-list {
      margin-top: 20px;

      .error-title {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 14px;
        font-weight: 600;
        color: #f56c6c;
        margin-bottom: 10px;
      }
    }
  }
}
</style>
