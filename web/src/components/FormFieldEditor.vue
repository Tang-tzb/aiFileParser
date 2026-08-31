<script lang="ts" setup>
import {ref, reactive, watch, computed} from 'vue'
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage} from 'element-plus'
import {FieldType, FIELD_TYPE_LABELS, type FormFieldCreateDTO} from '@/types/form'

/**
 * 表单字段编辑器（Dialog）
 * - 用于创建表单时配置字段 / 详情页追加字段
 * - 字段编码校验：^[a-zA-Z][a-zA-Z0-9_]*$，且在当前表单内唯一
 * - 通过 v-model:visible 控制显隐，confirm 事件回传字段数据
 */
const props = defineProps<{
  /** 是否显示 */
  visible: boolean
  /** 当前已有字段编码列表，用于唯一性校验 */
  existingCodes: string[]
  /** 编辑模式时的初始值（新增时为 null） */
  initialData?: FormFieldCreateDTO | null
  /** 当前最大排序号，新增时自动 +1 */
  maxSort: number
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  confirm: [field: FormFieldCreateDTO]
}>()

// 是否编辑模式
const isEdit = computed(() => !!props.initialData)

// 表单数据
const formRef = ref<FormInstance>()
const formData = reactive<FormFieldCreateDTO>({
  fieldName: '',
  fieldCode: '',
  fieldType: FieldType.STRING,
  required: false,
  description: '',
  sort: 1
})

// 字段类型选项
const fieldTypeOptions = Object.values(FieldType).map((t) => ({
  label: FIELD_TYPE_LABELS[t],
  value: t
}))

// 校验规则
const rules: FormRules = {
  fieldName: [
    {required: true, message: '请输入字段名称', trigger: 'blur'},
    {max: 100, message: '字段名称最长100字符', trigger: 'blur'}
  ],
  fieldCode: [
    {required: true, message: '请输入字段编码', trigger: 'blur'},
    {max: 64, message: '字段编码最长64字符', trigger: 'blur'},
    {
      pattern: /^[a-zA-Z][a-zA-Z0-9_]*$/,
      message: '须以字母开头且仅含字母数字下划线',
      trigger: 'blur'
    },
    {
      validator: (_rule, value: string, callback) => {
        // 编辑模式且编码未改变时不校验唯一性
        if (isEdit.value && props.initialData?.fieldCode === value) {
          callback()
          return
        }
        if (value && props.existingCodes.includes(value)) {
          callback(new Error('字段编码在当前表单内已存在'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  fieldType: [{required: true, message: '请选择字段类型', trigger: 'change'}],
  description: [{max: 500, message: '字段描述最长500字符', trigger: 'blur'}]
}

// 监听显隐与初始数据，重置表单
watch(
    () => props.visible,
    (val) => {
      if (val) {
        if (props.initialData) {
          Object.assign(formData, props.initialData)
        } else {
          Object.assign(formData, {
            fieldName: '',
            fieldCode: '',
            fieldType: FieldType.STRING,
            required: false,
            description: '',
            sort: props.maxSort + 1
          })
        }
        formRef.value?.clearValidate()
      }
    }
)

// 关闭弹窗
function handleClose() {
  emit('update:visible', false)
}

// 确认提交
async function handleConfirm() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
    emit('confirm', {...formData})
    emit('update:visible', false)
  } catch {
    // 校验失败不关闭
  }
}

// 字段编码输入时自动转大写无要求，但去除空格
function handleCodeInput(val: string) {
  formData.fieldCode = val.replace(/\s/g, '')
}
</script>

<template>
  <el-dialog
      :close-on-click-modal="false"
      :model-value="visible"
      :title="isEdit ? '编辑字段' : '添加字段'"
      append-to-body
      width="520px"
      @close="handleClose"
  >
    <el-form
        ref="formRef"
        :model="formData"
        :rules="rules"
        label-position="right"
        label-width="90px"
    >
      <el-form-item label="字段名称" prop="fieldName">
        <el-input v-model="formData.fieldName" maxlength="100" placeholder="如：项目名称" show-word-limit/>
      </el-form-item>
      <el-form-item label="字段编码" prop="fieldCode">
        <el-input
            :disabled="isEdit"
            :model-value="formData.fieldCode"
            maxlength="64"
            placeholder="如：projectName"
            show-word-limit
            @update:model-value="handleCodeInput"
        />
      </el-form-item>
      <el-form-item label="字段类型" prop="fieldType">
        <el-select v-model="formData.fieldType" :disabled="isEdit" placeholder="请选择字段类型" style="width: 100%">
          <el-option
              v-for="opt in fieldTypeOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="是否必填" prop="required">
        <el-switch v-model="formData.required"/>
      </el-form-item>
      <el-form-item label="排序号" prop="sort">
        <el-input-number v-model="formData.sort" :max="9999" :min="1" controls-position="right"/>
      </el-form-item>
      <el-form-item label="字段描述" prop="description">
        <el-input
            v-model="formData.description"
            :rows="2"
            maxlength="500"
            placeholder="便于 AI 理解该字段含义（可选）"
            show-word-limit
            type="textarea"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" @click="handleConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>
