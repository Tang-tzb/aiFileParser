<script lang="ts" setup>
import {computed, onMounted, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {addField, deleteField, getFormDetail} from '@/api/form'
import {FIELD_TYPE_LABELS, FieldType, type FormFieldCreateDTO, type FormFieldVO, type FormVO} from '@/types/form'
import FormFieldEditor from '@/components/FormFieldEditor.vue'
import ProjectBindDialog from '@/components/ProjectBindDialog.vue'

/**
 * 表单详情页
 * - 展示表单头信息 + 字段列表
 * - 支持追加字段（POST /form/{id}/field）
 * - 支持删除字段（DELETE /form/{id}/field/{fieldId}）
 * - 支持绑定项目（同一表单可绑定多个项目，6005 同项目重复绑定由拦截器提示）
 */
const route = useRoute()
const router = useRouter()

// 绑定项目弹窗显隐
const bindVisible = ref(false)

// 表单 ID（保持字符串，避免大整数精度丢失）
const formId = computed(() => route.params.id as string)

// 加载状态
const loading = ref(false)
// 表单详情数据
const formDetail = ref<FormVO | null>(null)

// 字段编辑器（仅用于追加字段，后端无字段编辑接口）
const editorVisible = ref(false)

// 已有字段编码列表（用于唯一性校验）
const existingCodes = computed(() =>
    formDetail.value?.fields?.map((f) => f.fieldCode) ?? []
)

// 当前最大排序号
const maxSort = computed(() =>
    formDetail.value?.fields?.reduce((max, f) => Math.max(max, f.sort ?? 0), 0) ?? 0
)

// 加载表单详情
async function loadFormDetail() {
  loading.value = true
  try {
    const data = await getFormDetail(formId.value)
    formDetail.value = data
  } catch {
    // 错误统一处理
  } finally {
    loading.value = false
  }
}

// 追加字段
function handleAddField() {
  editorVisible.value = true
}

// 字段编辑器确认：调用 addField 接口
async function handleFieldConfirm(field: FormFieldCreateDTO) {
  if (!formDetail.value) return
  try {
    await addField(formId.value, field)
    ElMessage.success(`字段"${field.fieldName}"添加成功`)
    // 重新拉取详情
    await loadFormDetail()
  } catch {
    // 错误统一处理
  }
}

// 删除字段
async function handleDeleteField(row: FormFieldVO) {
  if (!formDetail.value) return
  try {
    await ElMessageBox.confirm(`确定删除字段"${row.fieldName}"吗？`, '提示', {
      type: 'warning'
    })
    await deleteField(formId.value, row.fieldId)
    ElMessage.success('字段已删除')
    await loadFormDetail()
  } catch {
    // 取消或错误
  }
}

// 返回列表
function handleBack() {
  router.push('/form/list')
}

// 格式化时间
function formatTime(time?: string): string {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

// 字段类型标签类型
function fieldTypeTagType(type: FieldType) {
  const map: Record<FieldType, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
    [FieldType.STRING]: 'primary',
    [FieldType.INTEGER]: 'success',
    [FieldType.DECIMAL]: 'warning',
    [FieldType.DATE]: 'info',
    [FieldType.BOOLEAN]: 'danger'
  }
  return map[type]
}

onMounted(() => {
  if (!formId.value || !/^\d+$/.test(formId.value)) {
    ElMessage.error('表单 ID 无效')
    router.replace('/form/list')
    return
  }
  loadFormDetail()
})
</script>

<template>
  <div v-loading="loading" class="form-detail-page">
    <!-- 表单不存在时 -->
    <el-empty v-if="!loading && !formDetail" description="表单不存在或加载失败">
      <el-button type="primary" @click="handleBack">返回列表</el-button>
    </el-empty>

    <template v-if="formDetail">
      <!-- 表单基本信息 -->
      <el-card class="info-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span class="card-title">
              <el-icon><Document/></el-icon>
              {{ formDetail.formName }}
            </span>
            <div class="header-actions">
              <!-- 绑定项目（同一表单可绑定多个项目） -->
              <el-button plain type="primary" @click="bindVisible = true">
                <el-icon>
                  <Link/>
                </el-icon>
                绑定项目
              </el-button>
              <el-button @click="handleBack">
                <el-icon>
                  <Back/>
                </el-icon>
                返回列表
              </el-button>
            </div>
          </div>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="表单 ID">{{ formDetail.formId }}</el-descriptions-item>
          <el-descriptions-item label="表单名称">{{ formDetail.formName }}</el-descriptions-item>
          <el-descriptions-item label="字段数量">{{ formDetail.fields?.length ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(formDetail.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatTime(formDetail.updateTime) }}</el-descriptions-item>
          <el-descriptions-item :span="2" label="描述">
            {{ formDetail.description || '-' }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 字段列表 -->
      <el-card class="fields-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span class="card-title">
              <el-icon><Setting/></el-icon>
              字段列表
              <el-tag round size="small" type="info">{{ formDetail.fields?.length ?? 0 }} 个</el-tag>
            </span>
            <el-button type="primary" @click="handleAddField">
              <el-icon>
                <Plus/>
              </el-icon>
              追加字段
            </el-button>
          </div>
        </template>

        <el-empty v-if="!formDetail.fields || formDetail.fields.length === 0" description="该表单暂无字段">
          <template #image>
            <el-icon :size="56" color="#c0c4cc">
              <Files/>
            </el-icon>
          </template>
        </el-empty>

        <el-table v-else :data="formDetail.fields" border stripe>
          <el-table-column align="center" label="排序" prop="sort" width="70"/>
          <el-table-column label="字段名称" min-width="140" prop="fieldName"/>
          <el-table-column label="字段编码" min-width="140" prop="fieldCode">
            <template #default="{ row }">
              <code class="field-code">{{ row.fieldCode }}</code>
            </template>
          </el-table-column>
          <el-table-column align="center" label="类型" width="100">
            <template #default="{ row }">
              <el-tag :type="fieldTypeTagType(row.fieldType)" size="small">
                {{ FIELD_TYPE_LABELS[row.fieldType as FieldType] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column align="center" label="必填" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.required" size="small" type="danger">必填</el-tag>
              <span v-else class="text-muted">否</span>
            </template>
          </el-table-column>
          <el-table-column label="描述" min-width="180" prop="description" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.description || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="100">
            <template #default="{ row }">
              <el-button
                  link
                  size="small"
                  type="danger"
                  @click="handleDeleteField(row as FormFieldVO)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- 字段编辑器（追加字段） -->
    <FormFieldEditor
        v-model:visible="editorVisible"
        :existing-codes="existingCodes"
        :initial-data="null"
        :max-sort="maxSort"
        @confirm="handleFieldConfirm"
    />

    <!-- 绑定项目弹窗（同一表单可绑定多个项目） -->
    <ProjectBindDialog
        v-model:visible="bindVisible"
        :form-id="formId"
        :form-name="formDetail?.formName"
    />
  </div>
</template>

<style lang="scss" scoped>
.form-detail-page {
  max-width: 1200px;
  margin: 0 auto;

  .info-card,
  .fields-card {
    margin-bottom: 16px;
  }

  .card-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: 600;
  }

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .field-code {
    background: #f5f7fa;
    padding: 2px 6px;
    border-radius: 4px;
    font-family: 'Courier New', monospace;
    font-size: 12px;
    color: #409eff;
  }

  .text-muted {
    color: #c0c4cc;
  }
}
</style>
