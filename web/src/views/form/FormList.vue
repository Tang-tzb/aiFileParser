<script lang="ts" setup>
import {onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {deleteForm, getFormDetail, getFormList} from '@/api/form'
import type {FormVO} from '@/types/form'
import ProjectBindDialog from '@/components/ProjectBindDialog.vue'

/**
 * 表单管理列表页
 *
 * - 对接后端 GET /form/page 分页接口，按创建时间倒序展示表单列表
 * - 顶部保留"按表单 ID 查询"入口（GET /form/{id}）与"新建表单"按钮
 * - 列表项支持"查看详情"与"删除"（DELETE /form/{id}，级联软删字段）
 */
const router = useRouter()

// 按 ID 查询（用字符串保存，避免雪花算法大整数超出 JS 安全整数范围导致精度丢失）
const queryId = ref('')
const querying = ref(false)

// 列表数据与分页状态
const list = ref<FormVO[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)

// 限制只能输入数字（保持字符串类型，避免精度丢失）
function handleIdInput(val: string) {
  queryId.value = val.replace(/\D/g, '')
}

// 查询表单详情
async function handleQueryById() {
  if (!queryId.value) {
    ElMessage.warning('请输入表单 ID')
    return
  }
  querying.value = true
  try {
    await getFormDetail(queryId.value)
    // 进入详情页
    router.push(`/form/${queryId.value}`)
  } catch {
    // 错误已由 axios 拦截器统一提示
  } finally {
    querying.value = false
  }
}

// 跳转新建表单
function handleCreate() {
  router.push('/form/create')
}

// 加载表单列表
async function loadList() {
  loading.value = true
  try {
    const data = await getFormList({pageNum: pageNum.value, pageSize: pageSize.value})
    list.value = data?.records ?? []
    total.value = data?.total ?? 0
  } catch {
    // 错误已由 axios 拦截器统一提示
  } finally {
    loading.value = false
  }
}

// 分页切换
function handlePageChange(p: number) {
  pageNum.value = p
  loadList()
}

// 进入详情页
function handleViewDetail(formId: number | string) {
  router.push(`/form/${formId}`)
}

// ===== 绑定项目（同一表单可绑定多个项目，6005 同项目重复绑定由拦截器提示） =====

// 绑定弹窗显隐
const bindVisible = ref(false)
// 当前绑定的表单 ID / 名称（ID 字符串化避免大整数精度丢失）
const bindFormId = ref('')
const bindFormName = ref('')

// 打开绑定项目弹窗
function handleBindProject(row: FormVO) {
  bindFormId.value = String(row.formId)
  bindFormName.value = row.formName
  bindVisible.value = true
}

// 删除表单
async function handleDelete(row: FormVO) {
  try {
    await ElMessageBox.confirm(
        `确定删除表单「${row.formName}」及其全部字段吗？`,
        '提示',
        {type: 'warning'}
    )
    await deleteForm(row.formId)
    ElMessage.success('已删除')
    // 删除后若当前页只剩 1 条且非首页，回退一页，避免空页
    if (list.value.length === 1 && pageNum.value > 1) {
      pageNum.value -= 1
    }
    await loadList()
  } catch {
    // 取消或错误（错误已由拦截器提示）
  }
}

onMounted(loadList)
</script>

<template>
  <div class="form-list-page">
    <!-- 操作区 -->
    <el-card class="action-card" shadow="never">
      <div class="action-bar">
        <div class="left-group">
          <el-input
              v-model="queryId"
              class="id-input"
              clearable
              placeholder="输入表单 ID"
              @input="handleIdInput"
          />
          <el-button :loading="querying" type="primary" @click="handleQueryById">
            <el-icon>
              <Search/>
            </el-icon>
            查询表单
          </el-button>
        </div>
        <div class="right-group">
          <el-button type="success" @click="handleCreate">
            <el-icon>
              <Plus/>
            </el-icon>
            新建表单
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 表单列表 -->
    <el-card class="list-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>
            <el-icon><Document/></el-icon>
            表单列表
          </span>
        </div>
      </template>

      <el-empty v-if="!loading && list.length === 0" description="暂无表单，请通过上方查询或新建表单">
        <template #image>
          <el-icon :size="56" color="#c0c4cc">
            <Document/>
          </el-icon>
        </template>
      </el-empty>

      <el-table v-else v-loading="loading" :data="list" stripe>
        <el-table-column label="表单 ID" prop="formId" width="200"/>
        <el-table-column label="表单名称" min-width="160" prop="formName"/>
        <el-table-column label="描述" min-width="200" prop="description" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">
            {{ row.createTime ? new Date(row.createTime).toLocaleString('zh-CN') : '-' }}
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="230">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="handleViewDetail(row.formId)">
              查看详情
            </el-button>
            <el-button link size="small" type="primary" @click="handleBindProject(row as FormVO)">
              绑定项目
            </el-button>
            <el-button link size="small" type="danger" @click="handleDelete(row as FormVO)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
            :current-page="pageNum"
            :page-size="pageSize"
            :total="total"
            background
            layout="total, prev, pager, next, jumper"
            @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <!-- 绑定项目弹窗（同一表单可绑定多个项目） -->
    <ProjectBindDialog v-model:visible="bindVisible" :form-id="bindFormId" :form-name="bindFormName"/>
  </div>
</template>

<style lang="scss" scoped>
.form-list-page {
  max-width: 1200px;
  margin: 0 auto;

  .action-card {
    margin-bottom: 16px;

    .action-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 12px;

      .left-group {
        display: flex;
        gap: 8px;

        .id-input {
          width: 200px;
        }
      }
    }
  }

  .list-card {
    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;

      span {
        display: flex;
        align-items: center;
        gap: 6px;
        font-weight: 600;
      }
    }

    .pagination-bar {
      margin-top: 12px;
      text-align: right;
    }
  }
}
</style>
