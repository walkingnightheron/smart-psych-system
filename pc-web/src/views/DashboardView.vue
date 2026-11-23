<script setup>
const stats = [
  { label: '问卷总数', value: 0 },
  { label: '进行中活动', value: 0 },
  { label: '有效答卷', value: 0 },
  { label: '待生成报告', value: 0 }
]
</script>

<template>
  <div>
    <el-alert
      title="骨架阶段：页面结构与样式已就绪，业务数据待接入"
      type="info"
      :closable="false"
      show-icon
      class="tip"
    />
    <el-row :gutter="16">
      <el-col v-for="item in stats" :key="item.label" :span="6">
        <el-card shadow="hover">
          <div class="stat-label">{{ item.label }}</div>
          <div class="stat-value">{{ item.value }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" class="next">
      <template #header>下一步</template>
      <p>1. REQ01 身份与个人信息：登录、当前用户、角色与数据范围校验</p>
      <p>2. REQ02 / REQ03 问卷配置、发布与版本</p>
      <p>3. REQ04 活动管理：名单、时间窗、状态流转</p>
      <p>4. REQ07 / REQ08 计分报告与结果统计</p>
    </el-card>
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: 16px;
}

.stat-label {
  color: #909399;
  font-size: 13px;
}

.stat-value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 600;
}

.next {
  margin-top: 16px;
}

.next p {
  margin: 6px 0;
  color: #606266;
}
</style>
