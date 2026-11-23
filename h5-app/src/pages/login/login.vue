<script setup>
import { reactive, ref } from 'vue'
import { request } from '../../api/request'

const loading = ref(false)
const form = reactive({
  username: '',
  password: ''
})

async function submit() {
  if (!form.username || !form.password) {
    uni.showToast({ title: '请输入账号和密码', icon: 'none' })
    return
  }
  loading.value = true
  try {
    const res = await request({ url: '/auth/login', method: 'POST', data: { ...form } })
    uni.setStorageSync('token', res.data?.token ?? '')
    uni.reLaunch({ url: '/pages/activity/list' })
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

function skip() {
  uni.reLaunch({ url: '/pages/activity/list' })
}
</script>

<template>
  <view class="page">
    <view class="header">
      <view class="logo">心</view>
      <view class="title">智慧心理健康评估</view>
      <view class="subtitle">学生端 · 问卷填写与报告查看</view>
    </view>

    <view class="form-card">
      <input v-model="form.username" class="field" placeholder="学号 / 工号" />
      <input v-model="form.password" class="field" password placeholder="密码" />
      <button class="primary-btn" :loading="loading" @tap="submit">登录</button>
      <view class="link" @tap="skip">暂不登录，先看页面</view>
    </view>

    <view class="hint">骨架阶段：登录接口 /api/auth/login 待按 REQ01 实现</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 120rpx 48rpx 48rpx;
  background: linear-gradient(180deg, #eaf2ff 0%, #f5f7fa 40%);
}

.header {
  text-align: center;
}

.logo {
  width: 112rpx;
  height: 112rpx;
  margin: 0 auto 32rpx;
  border-radius: 32rpx;
  background: #409eff;
  color: #fff;
  font-size: 56rpx;
  line-height: 112rpx;
  text-align: center;
}

.title {
  color: #303133;
  font-size: 40rpx;
  font-weight: 600;
}

.subtitle {
  margin-top: 12rpx;
  color: #909399;
  font-size: 26rpx;
}

.form-card {
  margin-top: 72rpx;
  padding: 40rpx 32rpx;
  border-radius: 24rpx;
  background: #fff;
  box-shadow: 0 12rpx 32rpx rgba(64, 158, 255, 0.08);
}

.field {
  height: 96rpx;
  padding: 0 28rpx;
  margin-bottom: 24rpx;
  border: 1px solid #e4e7ed;
  border-radius: 16rpx;
  background: #f9fafc;
  font-size: 30rpx;
}

.primary-btn {
  margin-top: 16rpx;
  border-radius: 16rpx;
  background: #409eff;
  color: #fff;
  font-size: 32rpx;
  line-height: 96rpx;
}

.link {
  margin-top: 28rpx;
  color: #409eff;
  font-size: 26rpx;
  text-align: center;
}

.hint {
  margin-top: 48rpx;
  color: #c0c4cc;
  font-size: 24rpx;
  text-align: center;
}
</style>
