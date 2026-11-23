<script setup>
import { reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { loginApi } from '../../api/h5'

const loading = ref(false)
const activeDemoUsername = ref('')

const form = reactive({
  username: '',
  password: ''
})

const demoAccounts = [
  {
    username: '20260003',
    name: '许星河',
    state: '待填写',
    stateType: 'normal'
  },
  {
    username: '20260001',
    name: '苏晴',
    state: '已交卷',
    stateType: 'done'
  },
  {
    username: '20260002',
    name: '陆远',
    state: '已交卷',
    stateType: 'done'
  },
  {
    username: '20260004',
    name: '赵子墨',
    state: '名单外',
    stateType: 'muted'
  }
]

onLoad(() => {
  const token = uni.getStorageSync('token')
  const cachedUser = uni.getStorageSync('user')
  if (token && cachedUser?.role === 'STUDENT') {
    form.username = cachedUser.username || ''
  }
})

function applyDemoAccount(account, autoSubmit = false) {
  activeDemoUsername.value = account.username
  form.username = account.username
  form.password = '123456'
  if (autoSubmit) {
    submit()
  } else {
    uni.showToast({
      title: `已填入 ${account.name}（${account.username}）`,
      icon: 'none'
    })
  }
}

async function submit() {
  const username = form.username.trim()
  const password = form.password

  if (!username) {
    uni.showToast({ title: '请输入学号账号', icon: 'none' })
    return
  }
  if (!password) {
    uni.showToast({ title: '请输入登录密码', icon: 'none' })
    return
  }

  loading.value = true
  try {
    const res = await loginApi({ username, password })
    const token = res?.data?.token || ''
    const user = res?.data?.user || null

    if (!token || !user) {
      uni.showToast({ title: '登录返回数据异常', icon: 'none' })
      return
    }

    if (user.role !== 'STUDENT') {
      uni.showModal({
        title: '请使用学生参与者账号登录H5端',
        content: `当前账号（${user.username}）角色为「${user.role}」，H5 参与端仅面向学生（STUDENT）开放。建议重新选择学生账号，或继续进入以验证服务端 403 角色权限拦截。`,
        confirmText: '重新选择',
        cancelText: '验证403拦截',
        confirmColor: '#2055A4',
        success: (modalRes) => {
          if (modalRes.confirm) {
            uni.removeStorageSync('token')
            uni.removeStorageSync('user')
          } else if (modalRes.cancel) {
            uni.setStorageSync('token', token)
            uni.setStorageSync('user', user)
            uni.reLaunch({ url: '/pages/activity/list' })
          }
        }
      })
      return
    }

    uni.setStorageSync('token', token)
    uni.setStorageSync('user', user)
    uni.showToast({ title: `欢迎，${user.realName || user.username}`, icon: 'none' })
    setTimeout(() => {
      uni.reLaunch({ url: '/pages/activity/list' })
    }, 200)
  } catch (error) {
    // Request layer already displays clear error message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <view class="login-page">
    <view class="header">
      <view class="title">学生心理评估</view>
      <view class="subtitle">学校心理健康教育中心</view>
    </view>

    <view class="form-card">
      <view class="input-row">
        <text class="input-label">学号</text>
        <input
          v-model="form.username"
          class="input-control"
          placeholder="请输入学号"
          placeholder-class="input-placeholder"
          maxlength="32"
        />
      </view>

      <view class="input-row">
        <text class="input-label">密码</text>
        <input
          v-model="form.password"
          class="input-control"
          password
          placeholder="请输入密码"
          placeholder-class="input-placeholder"
          maxlength="64"
          @confirm="submit"
        />
      </view>

      <button
        class="login-btn"
        :class="{ 'is-loading': loading }"
        :disabled="loading"
        :loading="loading"
        @tap="submit"
      >
        {{ loading ? '登录中...' : '登录' }}
      </button>
    </view>

    <view class="demo-section">
      <view class="demo-title">演示账号（口令 123456）</view>
      <view class="demo-grid">
        <view
          v-for="item in demoAccounts"
          :key="item.username"
          class="demo-cell"
          :class="{ 'is-active': form.username === item.username || activeDemoUsername === item.username }"
          @tap="applyDemoAccount(item, false)"
        >
          <view class="demo-cell-main">
            <text class="demo-name">{{ item.name }}</text>
            <text class="demo-id">{{ item.username }}</text>
          </view>
          <view class="demo-state-wrap">
            <text class="demo-state" :class="`state-${item.stateType}`">{{ item.state }}</text>
            <text class="demo-login-link" @tap.stop="applyDemoAccount(item, true)">一键登录</text>
          </view>
        </view>
      </view>
    </view>

    <view class="footer-note">
      本系统为高校软件工程实训模拟数据，结果不作为医学或临床诊断依据。
    </view>
  </view>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  padding: 96rpx 40rpx 64rpx;
  background: #FFFFFF;
}

.header {
  margin-bottom: 64rpx;
}

.title {
  color: #1A1F2C;
  font-size: 48rpx;
  font-weight: 700;
  letter-spacing: 2rpx;
}

.subtitle {
  margin-top: 14rpx;
  color: #525B6B;
  font-size: 28rpx;
}

.form-card {
  padding: 8rpx 0;
}

.input-row {
  margin-bottom: 32rpx;
}

.input-label {
  display: block;
  margin-bottom: 14rpx;
  color: #1A1F2C;
  font-size: 28rpx;
  font-weight: 600;
}

.input-control {
  width: 100%;
  height: 92rpx;
  padding: 0 24rpx;
  border-radius: 10rpx;
  background: #FAFBFC;
  border: 1rpx solid #E8EAEE;
  color: #1A1F2C;
  font-size: 30rpx;
}

.input-control:focus {
  border-color: #2055A4;
  background: #FFFFFF;
}

.input-placeholder {
  color: #8A93A2;
  font-size: 28rpx;
}

.login-btn {
  margin-top: 16rpx;
  width: 100%;
  height: 96rpx;
  line-height: 96rpx;
  border-radius: 10rpx;
  background: #2055A4;
  color: #FFFFFF;
  font-size: 32rpx;
  font-weight: 600;
  text-align: center;
}

.login-btn:active {
  background: #184282;
}

.login-btn.is-loading {
  opacity: 0.85;
}

.demo-section {
  margin-top: 64rpx;
}

.demo-title {
  color: #525B6B;
  font-size: 24rpx;
  margin-bottom: 20rpx;
}

.demo-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
}

.demo-cell {
  width: calc(50% - 10rpx);
  padding: 22rpx 20rpx;
  border-radius: 10rpx;
  background: #FFFFFF;
  border: 1rpx solid #E8EAEE;
}

.demo-cell.is-active {
  background: #EFF4FC;
  border-color: #2055A4;
}

.demo-cell-main {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
  margin-bottom: 14rpx;
}

.demo-name {
  color: #1A1F2C;
  font-size: 28rpx;
  font-weight: 600;
}

.demo-id {
  color: #525B6B;
  font-size: 24rpx;
  font-variant-numeric: tabular-nums;
}

.demo-state-wrap {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.demo-state {
  font-size: 22rpx;
  padding: 4rpx 12rpx;
  border-radius: 6rpx;
}

.state-normal {
  background: #EFF4FC;
  color: #2055A4;
}

.state-done {
  background: #E8F5EE;
  color: #1E7E4E;
}

.state-muted {
  background: #F5F6F8;
  color: #8A93A2;
}

.demo-login-link {
  color: #2055A4;
  font-size: 22rpx;
  padding: 4rpx 8rpx;
}

.footer-note {
  margin-top: 56rpx;
  color: #8A93A2;
  font-size: 22rpx;
  line-height: 1.7;
  text-align: center;
}
</style>
