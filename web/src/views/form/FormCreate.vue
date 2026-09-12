<script lang="ts" setup>
import {computed, reactive, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {createForm} from '@/api/form'
import {FIELD_TYPE_LABELS, FieldType, type FormCreateDTO, type FormFieldCreateDTO} from '@/types/form'
import FormFieldEditor from '@/components/FormFieldEditor.vue'
import ProjectBindDialog from '@/components/ProjectBindDialog.vue'

/**
 * 创建表单页
 * - 表单头信息 + 动态字段配置（表格 + Dialog）
 * - 字段编码唯一性校验
 * - 一次性提交：POST /form/create（表单 + 字段同事务）
 */
const router = useRouter()

// 表单头表单引用
const formRef = ref<FormInstance>()
// 表单头数据
const formData = reactive({
  formName: '',
  description: ''
})

// 表单头校验规则
const rules: FormRules = {
  formName: [
    {required: true, message: '请输入表单名称', trigger: 'blur'},
    {max: 100, message: '表单名称最长100字符', trigger: 'blur'}
  ],
  description: [{max: 500, message: '表单描述最长500字符', trigger: 'blur'}]
}

// 字段列表（待提交）
const fieldList = ref<FormFieldCreateDTO[]>([])

// 字段编辑器显隐
const editorVisible = ref(false)
// 当前编辑的字段（编辑模式）
const editingField = ref<FormFieldCreateDTO | null>(null)
// 当前编辑字段在列表中的索引
const editingIndex = ref(-1)

// 已有字段编码列表（用于唯一性校验）
const existingCodes = computed(() => fieldList.value.map((f) => f.fieldCode))

// 当前最大排序号
const maxSort = computed(() =>
    fieldList.value.reduce((max, f) => Math.max(max, f.sort ?? 0), 0)
)

// 添加字段
function handleAddField() {
  editingField.value = null
  editingIndex.value = -1
  editorVisible.value = true
}

// 编辑字段
function handleEditField(row: FormFieldCreateDTO, index: number) {
  editingField.value = {...row}
  editingIndex.value = index
  editorVisible.value = true
}

// 字段编辑器确认回调
function handleFieldConfirm(field: FormFieldCreateDTO) {
  if (editingIndex.value >= 0) {
    // 编辑模式：替换原位置
    fieldList.value.splice(editingIndex.value, 1, field)
  } else {
    // 新增模式：追加
    fieldList.value.push(field)
  }
}

// 删除字段
async function handleDeleteField(row: FormFieldCreateDTO, index: number) {
  try {
    await ElMessageBox.confirm(`确定删除字段"${row.fieldName}"吗？`, '提示', {
      type: 'warning'
    })
    fieldList.value.splice(index, 1)
    ElMessage.success('已删除')
  } catch {
    // 取消
  }
}

// 提交保存表单
const submitting = ref(false)

async function handleSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    ElMessage.warning('请完善表单基本信息')
    return
  }

  const payload: FormCreateDTO = {
    formName: formData.formName,
    description: formData.description || undefined,
    fields: fieldList.value.length ? fieldList.value : undefined
  }

  submitting.value = true
  try {
    const formId = await createForm(payload)
    ElMessage.success(`表单创建成功，ID：${formId}`)
    await confirmBindAfterCreate(formId)
  } catch {
    // 错误已统一处理
  } finally {
    submitting.value = false
  }
}

// ===== 创建成功后的绑定项目入口（同一表单可绑定多个项目） =====

// 绑定弹窗显隐
const bindVisible = ref(false)
// 新创建的表单 ID（绑定用，字符串避免大整数精度丢失）
const createdFormId = ref('')

/**
 * 创建成功后询问是否立即绑定到项目
 * - 去绑定：打开 ProjectBindDialog（可连续绑定多个项目），弹窗关闭后跳表单详情
 * - 稍后：直接跳表单详情（保留旧路径）
 */
async function confirmBindAfterCreate(formId: number | string) {
  let goBind = false
  try {
    await ElMessageBox.confirm('是否立即绑定到项目？同一表单可绑定多个项目。', '绑定项目', {
      confirmButtonText: '去绑定',
      cancelButtonText: '稍后',
      type: 'info'
    })
    goBind = true
  } catch {
    goBind = false
  }

  if (goBind) {
    createdFormId.value = String(formId)
    bindVisible.value = true
  } else {
    router.replace(`/form/${formId}`)
  }
}

/** 绑定弹窗关闭后跳转表单详情（收口） */
watch(bindVisible, (v) => {
  if (!v && createdFormId.value) {
    router.replace(`/form/${createdFormId.value}`)
    createdFormId.value = ''
  }
})

// 返回列表
function handleBack() {
  router.push('/form/list')
}

// 获取字段类型标签类型
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
</script>

<template>
  <div class="form-create-page">
    <!-- 表单基本信息 -->
    <el-card class="info-card" shadow="never">
      <template #header>
        <span class="card-title">
          <el-icon><Document/></el-icon>
          表单基本信息
        </span>
      </template>
      <el-form
          ref="formRef"
          :model="formData"
          :rules="rules"
          label-position="right"
          label-width="90px"
      >
        <el-form-item label="表单名称" prop="formName">
          <el-input v-model="formData.formName" maxlength="100" placeholder="如：项目申报表" show-word-limit/>
        </el-form-item>
        <el-form-item label="表单描述" prop="description">
          <el-input
              v-model="formData.description"
              :rows="2"
              maxlength="500"
              placeholder="表单用途说明（可选）"
              show-word-limit
              type="textarea"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 字段配置 -->
    <el-card class="fields-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">
            <el-icon><Setting/></el-icon>
            字段配置
            <el-tag round size="small" type="info">{{ fieldList.length }} 个字段</el-tag>
          </span>
          <el-button type="primary" @click="handleAddField">
            <el-icon>
              <Plus/>
            </el-icon>
            添加字段
          </el-button>
        </div>
      </template>

      <el-empty v-if="fieldList.length === 0" description="暂未配置字段，点击右上角添加">
        <template #image>
          <el-icon :size="56" color="#c0c4cc">
            <Files/>
          </el-icon>
        </template>
      </el-empty>

      <el-table v-else :data="fieldList" border stripe>
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
        <el-table-column fixed="right" label="操作" width="140">
          <template #default="{ row, $index }">
            <el-button link size="small" type="primary" @click="handleEditField(row as FormFieldCreateDTO, $index)">
              编辑
            </el-button>
            <el-button link size="small" type="danger" @click="handleDeleteField(row as FormFieldCreateDTO, $index)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 底部操作 -->
    <div class="footer-actions">
      <el-button @click="handleBack">取消</el-button>
      <el-button :loading="submitting" type="primary" @click="handleSubmit">
        保存表单
      </el-button>
    </div>

    <!-- 字段编辑器 -->
    <FormFieldEditor
        v-model:visible="editorVisible"
        :existing-codes="existingCodes"
        :initial-data="editingField"
        :max-sort="maxSort"
        @confirm="handleFieldConfirm"
    />

    <!-- 绑定项目弹窗（创建成功后可选绑定，同一表单可绑多个项目） -->
    <ProjectBindDialog v-model:visible="bindVisible" :form-id="createdFormId"/>
  </div>
</template>

<style lang="scss" scoped>
.form-create-page {
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

  .footer-actions {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    padding: 16px 0;
  }
}
</style>
