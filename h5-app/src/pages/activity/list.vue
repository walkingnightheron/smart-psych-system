<script setup>
function goFill() {
  uni.navigateTo({ url: '/pages/survey/fill' })
}

function goRecords() {
  uni.navigateTo({ url: '/pages/record/list' })
}

function goReport() {
  uni.navigateTo({ url: '/pages/report/report' })
}
</script>

<template>
  <view class="page">
    <view class="welcome">
      <view class="hello">你好，同学</view>
      <view class="sub">以下为教学模拟数据，仅用于功能验证</view>
    </view>

    <view class="section-title">进行中的活动</view>

    <view class="activity-card">
      <view class="row">
        <text class="activity-name">大学生心理健康普查（示例）</text>
        <text class="tag">待填写</text>
      </view>
      <view class="meta">开放时间：2026-11-24 08:00 ~ 2026-11-30 22:00</view>
      <view class="meta">问卷：示例问卷 · 3 题 · 单选题</view>
      <button class="fill-btn" @tap="goFill">去填写</button>
    </view>

    <view class="links">
      <view class="link-card" @tap="goRecords">
        <text class="link-title">我的记录</text>
        <text class="link-desc">查看提交记录</text>
      </view>
      <view class="link-card" @tap="goReport">
        <text class="link-title">我的报告</text>
        <text class="link-desc">查看评估结果</text>
      </view>
    </view>

    <view class="footer-tip">骨架阶段：活动列表待接入接口（REQ05）</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 32rpx;
}

.welcome {
  padding: 40rpx 32rpx;
  border-radius: 24rpx;
  background: linear-gradient(135deg, #409eff, #66b1ff);
  color: #fff;
}

.hello {
  font-size: 40rpx;
  font-weight: 600;
}

.sub {
  margin-top: 12rpx;
  font-size: 24rpx;
  opacity: 0.85;
}

.section-title {
  margin: 40rpx 8rpx 20rpx;
  color: #606266;
  font-size: 28rpx;
  font-weight: 600;
}

.activity-card {
  padding: 32rpx;
  border-radius: 24rpx;
  background: #fff;
  box-shadow: 0 8rpx 24rpx rgba(0, 0, 0, 0.04);
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.activity-name {
  flex: 1;
  color: #303133;
  font-size: 32rpx;
  font-weight: 600;
}

.tag {
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  background: #ecf5ff;
  color: #409eff;
  font-size: 22rpx;
}

.meta {
  margin-top: 16rpx;
  color: #909399;
  font-size: 24rpx;
}

.fill-btn {
  margin-top: 32rpx;
  border-radius: 16rpx;
  background: #409eff;
  color: #fff;
  font-size: 30rpx;
  line-height: 88rpx;
}

.links {
  display: flex;
  gap: 24rpx;
  margin-top: 32rpx;
}

.link-card {
  flex: 1;
  padding: 32rpx;
  border-radius: 24rpx;
  background: #fff;
  box-shadow: 0 8rpx 24rpx rgba(0, 0, 0, 0.04);
}

.link-title {
  display: block;
  color: #303133;
  font-size: 30rpx;
  font-weight: 600;
}

.link-desc {
  display: block;
  margin-top: 8rpx;
  color: #909399;
  font-size: 24rpx;
}

.footer-tip {
  margin-top: 64rpx;
  color: #c0c4cc;
  font-size: 24rpx;
  text-align: center;
}
</style>
