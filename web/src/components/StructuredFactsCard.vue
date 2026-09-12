<script lang="ts" setup>
import type {FieldValueItem, StructuredFieldFact} from '@/types/assistant'

/**
 * 字段事实卡片（Phase E，§8.4 双视觉状态）
 *
 * - conflict=false：正常行卡，展示 rawValue + unit + 来源（文件名 + 第 N 页）
 * - conflict=true：警告色冲突卡片，完整列出 values[] 全部来源值（不得只展示一条）
 * - 硬性规则：前端不得替用户挑选/排序/高亮某个值（与后端"冲突不裁决"同构）；
 *   normalizedValue 为后端计算值，仅透传展示、禁止参与任何前端计算
 */
defineProps<{
  /** 空数组不渲染，由父组件 v-if 控制 */
  facts: StructuredFieldFact[]
}>()

/** 格式化单条来源值："施工许可证.pdf 第3页 = 10亿元" */
function formatValue(v: FieldValueItem): string {
  const source = `${v.sourceFileName || '未知来源'}${v.sourcePage ? ` 第${v.sourcePage}页` : ''}`
  return `${source} = ${v.rawValue}${v.unit ?? ''}`
}
</script>

<template>
  <div class="facts-card">
    <div class="facts-title">
      <el-icon>
        <DataAnalysis/>
      </el-icon>
      字段事实（来自项目表单数据）
    </div>

    <template v-for="fact in facts" :key="fact.projectFormId + fact.fieldCode">
      <!-- 正常行卡：唯一事实 -->
      <div v-if="!fact.conflict" class="fact-row">
        <span class="fact-name">{{ fact.fieldName }}</span>
        <span class="fact-value">{{ fact.values[0]?.rawValue }}{{ fact.values[0]?.unit ?? '' }}</span>
        <span class="fact-source">
          来源：{{
            fact.values[0]?.sourceFileName || '未知来源'
          }}{{ fact.values[0]?.sourcePage ? ` 第${fact.values[0]?.sourcePage}页` : '' }}
        </span>
      </div>

      <!-- 冲突警告卡：列出全部来源值，不裁决 -->
      <el-alert v-else :closable="false" class="conflict-alert" type="warning">
        <template #title>
          {{ fact.fieldName }}存在多个来源值：{{ fact.values.map(formatValue).join('；') }}。
          无法仅根据现有资料确认最终口径。
        </template>
      </el-alert>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.facts-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background-color: #fafcff;

  .facts-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    font-weight: 600;
    color: #606266;
  }

  .fact-row {
    display: flex;
    align-items: baseline;
    gap: 12px;
    font-size: 13px;
    line-height: 1.6;

    .fact-name {
      font-weight: 600;
      color: #303133;
    }

    .fact-value {
      color: #409eff;
    }

    .fact-source {
      color: #909399;
    }
  }

  .conflict-alert {
    font-size: 13px;
  }
}
</style>
