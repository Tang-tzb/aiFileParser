<script lang="ts" setup>
import {computed, ref, watch} from 'vue'
import {ElMessage} from 'element-plus'
import {associateFile, bindProjectForm, getProjectPage} from '@/api/project'
import {PROJECT_STATUS_LABELS, ProjectStatus, type ProjectVO} from '@/types/project'
import type {SnowflakeId} from '@/types/api'

/**
 * 绑定项目弹窗（双模式）
 *
 * - form 模式（默认，Phase G）：表单 → 项目，同一表单可绑定多个项目（(projectId, formId) 唯一），
 *   同项目重复绑定 6005 由拦截器提示
 * - file 模式（Phase H）：文件 → 项目，文件为单归属（file_record.project_id 单值列），已归属
 *   其他项目的文件绑定报 6003（调用方前置仅传未归属文件）；绑本项目后端幂等跳过
 * - 候选：GET /project/page 分页列表（项目编号/名称/状态 + 行内"绑定"）
 * - 会话内已绑记录：绑定成功的项目打"已绑定"Tag 并禁用（无反查接口，刷新后标记重置）
 * - 绑定成功后 emit('bound')，父页面可按需刷新
 */
const props = withDefaults(defineProps<{
  /** 绑定类型：form=表单（默认）/ file=文件 */
  bindType?: 'form' | 'file'
  /** form 模式：待绑定的表单 ID */
  formId?: SnowflakeId
  /** file 模式：待绑定的文件 ID */
  fileId?: SnowflakeId
  /** 展示名（表单名 / 文件名） */
  formName?: string
  fileName?: string
}>(), {
  bindType: 'form'
})

const visible = defineModel<boolean>('visible', {default: false})
const emit = defineEmits<{ bound: [projectId: SnowflakeId] }>()

// 项目候选分页状态
const list = ref<ProjectVO[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 50
const loading = ref(false)

// 会话内已绑定项目 ID（绑定成功即记入，行内禁用防重复点击）
const boundProjectIds = ref<Set<string>>(new Set())
// 单行绑定中
const bindingId = ref('')

const dialogTitle = computed(() => {
  if (props.bindType === 'file') {
    return `绑定项目（文件：${props.fileName || props.fileId}）`
  }
  return `绑定项目${props.formName ? `：${props.formName}` : ''}（表单 ID：${props.formId}）`
})

const tipText = computed(() =>
    props.bindType === 'file'
        ? '一个文件只能归属一个项目；已归属其他项目的文件不可重复绑定。'
        : '同一表单可绑定到多个项目；同一项目重复绑定同一表单会被拒绝。'
)

/** 拉取项目候选列表 */
async function loadProjects() {
  loading.value = true
  try {
    const data = await getProjectPage({pageNum: pageNum.value, pageSize})
    list.value = data?.records ?? []
    total.value = data?.total ?? 0
  } catch {
    // 错误由拦截器统一提示
  } finally {
    loading.value = false
  }
}

/** 绑定到指定项目（按 bindType 分派；6005/6003 由拦截器统一提示） */
async function handleBind(row: ProjectVO) {
  const id = String(row.projectId)
  bindingId.value = id
  try {
    if (props.bindType === 'file') {
      if (!props.fileId) return
      await associateFile(id, props.fileId)
    } else {
      if (!props.formId) return
      await bindProjectForm(id, {formId: props.formId})
    }
    boundProjectIds.value.add(id)
    ElMessage.success(`已绑定到项目「${row.projectName}」`)
    emit('bound', row.projectId)
  } catch {
    // 错误由拦截器统一提示（6005 重复绑定 / 6003 已归属其他项目等）
  } finally {
    bindingId.value = ''
  }
}

/** 翻页 */
function handlePageChange(p: number) {
  pageNum.value = p
  loadProjects()
}

// 弹窗打开时初始化（重置分页与会话内标记）
watch(visible, (v) => {
  if (v) {
    pageNum.value = 1
    boundProjectIds.value = new Set()
    loadProjects()
  }
})
</script>

<template>
  <el-dialog
      v-model="visible"
      :title="dialogTitle"
      class="project-bind-dialog"
      width="640px"
  >
    <el-alert
        :closable="false"
        class="bind-tip"
        show-icon
        type="info"
    >
      {{ tipText }}
    </el-alert>

    <el-table v-loading="loading" :data="list" max-height="380" size="small">
      <el-table-column label="项目编号" min-width="120" prop="projectNo"/>
      <el-table-column label="项目名称" min-width="150" prop="projectName"/>
      <el-table-column align="center" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === ProjectStatus.ACTIVE ? 'success' : 'info'" size="small">
            {{ PROJECT_STATUS_LABELS[row.status as ProjectStatus] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column align="center" label="操作" width="100">
        <template #default="{ row }">
          <el-tag v-if="boundProjectIds.has(String(row.projectId))" size="small" type="success">
            已绑定
          </el-tag>
          <el-button
              v-else
              :loading="bindingId === String(row.projectId)"
              link
              size="small"
              type="primary"
              @click="handleBind(row as ProjectVO)"
          >
            绑定
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-bar">
      <el-pagination
          v-model:current-page="pageNum"
          :page-size="pageSize"
          :total="total"
          background
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
      />
    </div>

    <template #footer>
      <el-button type="primary" @click="visible = false">完成</el-button>
    </template>
  </el-dialog>
</template>

<style lang="scss" scoped>
.project-bind-dialog {
  .bind-tip {
    margin-bottom: 12px;
  }

  .pagination-bar {
    display: flex;
    justify-content: flex-end;
    margin-top: 12px;
  }
}
</style>
