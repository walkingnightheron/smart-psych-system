<script setup>
function goActivities() {
  uni.reLaunch({ url: '/pages/activity/list' })
}
</script>

<template>
  <view class="page">
    <view class="empty-card">
      <view class="empty-title">暂无提交记录</view>
      <view class="empty-desc">完成活动问卷后，提交记录将显示在这里</view>
    </view>
    <button class="back-btn" @tap="goActivities">返回活动列表</button>
    <view class="footer-tip">骨架阶段：个人记录待接入（REQ06）</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 32rpx;
}

.empty-card {
  padding: 96rpx 32rpx;
  border-radius: 24rpx;
  background: #fff;
  text-align: center;
}

.empty-title {
  color: #606266;
  font-size: 32rpx;
  font-weight: 600;
}

.empty-desc {
  margin-top: 16rpx;
  color: #909399;
  font-size: 26rpx;
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
