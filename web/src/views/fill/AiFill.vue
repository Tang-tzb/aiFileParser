<script lang="ts" setup>
// AI 自动填报页 - Phase 4 实现
</script>

<template>
  <div class="page-placeholder">
    <el-card>
      <el-empty description="AI 自动填报（选择表单 + 上传文件 → 启动解析）">
        <template #image>
          <el-icon :size="64" color="#409eff">
            <MagicStick/>
          </el-icon>
        </template>
        <el-button disabled plain type="primary">Phase 4 实现</el-button>
      </el-empty>
    </el-card>
  </div>
</template>

<style scoped>
.page-placeholder {
  max-width: 1200px;
  margin: 0 auto;
}
</style>
