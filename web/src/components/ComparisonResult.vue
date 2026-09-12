<script lang="ts" setup>
import type {ComparisonExcludedReason, CrossProjectComparisonVO, FieldValueItem} from '@/types/assistant'

/**
 * 跨项目比较结果卡（Phase E，§8.5 独立组件）
 *
 * 硬性规则：纯展示组件，禁止计算。
 * - 组件内禁止 import 任何计算逻辑，禁止对 normalizedValue 做 reduce/sort/差值运算
 * - rank / aggregates（max/min/sum/avg/count，后端 HALF_UP scale=4）/ diffFromCurrent
 *   均为后端直出，前端原样展示
 * - diffFromCurrent 为 null 的单元直接展示"—"（后端仅当前项目恰 1 个参与单元时计算）
 */
defineProps<{
  data: CrossProjectComparisonVO
}>()

/** 排除原因中文映射（禁止静默丢弃） */
const EXCLUDED_REASON_LABELS: Record<ComparisonExcludedReason, string> = {
  CONFLICT: '数据冲突',
  UNPARSEABLE: '值不可解析',
  UNIT_INCOMPATIBLE: '单位不兼容'
}

/** null/undefined 统一展示占位符 */
function dash(v?: string | number | null): string {
  return v === null || v === undefined || v === '' ? '—' : String(v)
}

/** 格式化来源："文件名 第N页" */
function formatSource(v: { sourceFileName?: string | null; sourcePage?: number | null }): string {
  return `${v.sourceFileName || '未知来源'}${v.sourcePage ? ` 第${v.sourcePage}页` : ''}`
}

/** 格式化单条来源值（排除明细复用） */
function formatValue(v: FieldValueItem): string {
  return `${v.sourceFileName || '未知来源'}${v.sourcePage ? ` 第${v.sourcePage}页` : ''} = ${v.rawValue}${v.unit ?? ''}`
}
</script>

<template>
  <div class="comparison-card">
    <div class="card-title">
      <el-icon>
        <TrendCharts/>
      </el-icon>
      跨项目比较：{{ data.fieldName }}（{{ data.unit || '无单位' }}）
    </div>

    <!-- 参与单元表（rank/差值均为后端直出） -->
    <el-table :data="data.units" border size="small">
      <el-table-column align="center" label="排名" prop="rank" width="70">
        <template #default="{row}">{{ dash(row.rank) }}</template>
      </el-table-column>
      <el-table-column label="项目" min-width="120" prop="projectName"/>
      <el-table-column label="数值" min-width="110">
        <template #default="{row}">{{ row.rawValue }}{{ row.unit ?? '' }}</template>
      </el-table-column>
      <el-table-column label="与当前项目差值" min-width="110">
        <template #default="{row}">{{ dash(row.diffFromCurrent) }}</template>
      </el-table-column>
      <el-table-column label="差值百分比" min-width="100">
        <template #default="{row}">{{ dash(row.diffFromCurrentPercent) }}</template>
      </el-table-column>
      <el-table-column label="来源" min-width="150">
        <template #default="{row}">{{ formatSource(row) }}</template>
      </el-table-column>
    </el-table>

    <!-- 聚合卡（后端计算结果直出） -->
    <el-descriptions v-if="data.aggregates" :column="5" border class="aggregates" size="small">
      <el-descriptions-item label="最大值">{{ data.aggregates.max }}</el-descriptions-item>
      <el-descriptions-item label="最小值">{{ data.aggregates.min }}</el-descriptions-item>
      <el-descriptions-item label="合计">{{ data.aggregates.sum }}</el-descriptions-item>
      <el-descriptions-item label="平均值">{{ data.aggregates.avg }}</el-descriptions-item>
      <el-descriptions-item label="数量">{{ data.aggregates.count }}</el-descriptions-item>
    </el-descriptions>

    <!-- 排除明细（附全部来源值，禁止静默丢弃） -->
    <div v-if="data.excluded?.length" class="excluded">
      <div class="excluded-title">未参与比较的项目：</div>
      <div v-for="(e, i) in data.excluded" :key="i" class="excluded-item">
        <el-tag size="small" type="warning">{{ EXCLUDED_REASON_LABELS[e.reason] }}</el-tag>
        <span class="excluded-project">{{ e.projectName }}</span>
        <span class="excluded-values">{{ e.values.map(formatValue).join('；') }}</span>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.comparison-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background-color: #fafcff;

  .card-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    font-weight: 600;
    color: #606266;
  }

  .aggregates {
    font-size: 13px;
  }

  .excluded {
    font-size: 13px;

    .excluded-title {
      color: #909399;
      margin-bottom: 4px;
    }

    .excluded-item {
      display: flex;
      align-items: baseline;
      gap: 8px;
      line-height: 1.8;

      .excluded-project {
        font-weight: 600;
        color: #303133;
      }

      .excluded-values {
        color: #909399;
      }
    }
  }
}
</style>
