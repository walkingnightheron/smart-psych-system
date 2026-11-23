<script setup>
function goActivities() {
  uni.reLaunch({ url: '/pages/activity/list' })
}
</script>

<template>
  <view class="page">
    <view class="report-card">
      <view class="report-header">
        <view class="report-title">评估报告</view>
        <view class="report-tag">暂无报告</view>
      </view>
      <view class="divider" />
      <view class="placeholder-row">
        <text class="placeholder-label">维度分</text>
        <text class="placeholder-value">--</text>
      </view>
      <view class="placeholder-row">
        <text class="placeholder-label">总分</text>
        <text class="placeholder-value">--</text>
      </view>
      <view class="placeholder-row">
        <text class="placeholder-label">结果区间</text>
        <text class="placeholder-value">--</text>
      </view>
      <view class="report-desc">报告生成后显示维度分、总分与结果区间，并关联问卷版本与规则版本。</view>
    </view>

    <button class="back-btn" @tap="goActivities">返回活动列表</button>
    <view class="footer-tip">骨架阶段：报告生成与查询待接入（REQ07 / REQ10）</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 32rpx;
}

.report-card {
  padding: 40rpx 32rpx;
  border-radius: 24rpx;
  background: #fff;
  box-shadow: 0 8rpx 24rpx rgba(0, 0, 0, 0.04);
}

.report-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.report-title {
  color: #303133;
  font-size: 36rpx;
  font-weight: 600;
}

.report-tag {
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  background: #f4f4f5;
  color: #909399;
  font-size: 22rpx;
}

.divider {
  height: 1px;
  margin: 28rpx 0;
  background: #f2f3f5;
}

.placeholder-row {
  display: flex;
  justify-content: space-between;
  padding: 20rpx 0;
}

.placeholder-label {
  color: #606266;
  font-size: 28rpx;
}

.placeholder-value {
  color: #303133;
  font-size: 28rpx;
  font-weight: 600;
}

.report-desc {
  margin-top: 24rpx;
  color: #909399;
  font-size: 24rpx;
  line-height: 1.6;
}

.back-btn {
  margin-top: 32rpx;
  border-radius: 16rpx;
  background: #fff;
  color: #409eff;
  font-size: 30rpx;
  line-height: 88rpx;
}

.footer-tip {
  margin-top: 48rpx;
  color: #c0c4cc;
  font-size: 24rpx;
  text-align: center;
}
</style>
