<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { loginApi } from '@/api/admin'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const loading = ref(false)
const form = reactive({
  username: 'admin01',
  password: ''
})

const demoAccounts = [
  { username: 'admin01', label: 'admin01 中心主任' },
  { username: 'admin02', label: 'admin02 授权辅导员' },
  { username: 'admin03', label: 'admin03 未授权对照' }
]

function fillDemoAccount(account) {
  form.username = account.username
  form.password = '123456'
  ElMessage.info(`已填入账号：${account.label}`)
}

async function submit() {
  const username = form.username.trim()
  const password = form.password
  if (!username || !password) {
    ElMessage.warning('请输入管理工号和登录密码')
    return
  }

  loading.value = true
  try {
    const res = await loginApi({ username, password })
    const data = res?.data ?? res
    const token = data?.token ?? ''
    const user = data?.user ?? null

    if (!token || !user) {
      ElMessage.error('登录响应异常，未获取到有效凭证')
      return
    }

    if (user.role !== 'ADMIN') {
      userStore.clear()
      ElMessage.error('权限不足（403）：当前为学生账号，请前往 H5 参与端登录')
      return
    }

    userStore.setToken(token)
    userStore.setProfile(user)
    ElMessage.success(`欢迎，${user.realName || user.username}`)

    const redirectPath = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    router.replace(redirectPath)
  } catch {
    // Error toast handled by axios interceptor
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-container">
      <div class="login-card">
        <div class="login-header">
          <h1 class="login-title">心理健康评估管理端</h1>
          <p class="login-subtitle">学校心理健康教育中心</p>
        </div>

        <el-form label-position="top" class="login-form" @submit.prevent="submit">
          <el-form-item label="管理工号">
            <el-input
              v-model="form.username"
              placeholder="请输入管理工号"
              size="large"
              clearable
              @keyup.enter="submit"
            >
              <template #prefix>
                <el-icon><User /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="登录密码">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入登录密码"
              size="large"
              show-password
              @keyup.enter="submit"
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="login-btn"
            :loading="loading"
            @click="submit"
          >
            登录
          </el-button>
        </el-form>
      </div>

      <div class="demo-bar">
        <span class="demo-tip">演示账号（口令 123456）：</span>
        <template v-for="(item, index) in demoAccounts" :key="item.username">
          <button
            type="button"
            class="demo-link"
            :class="{ active: form.username === item.username }"
            @click="fillDemoAccount(item)"
          >
            {{ item.label }}
          </button>
          <span v-if="index < demoAccounts.length - 1" class="demo-sep">|</span>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  background-color: #F5F6F8;
}

.login-container {
  width: 100%;
  max-width: 400px;
}

.login-card {
  width: 100%;
  padding: 32px 28px 28px;
  background-color: #FFFFFF;
  border: 1px solid #E5E7EB;
  border-radius: 8px;
}

.login-header {
  margin-bottom: 24px;
  text-align: center;
}

.login-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #111827;
}

.login-subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  color: #6B7280;
}

.login-form :deep(.el-form-item__label) {
  font-size: 13px;
  font-weight: 500;
  color: #4B5563;
  padding-bottom: 6px;
}

.login-btn {
  width: 100%;
  margin-top: 8px;
  height: 40px;
  font-size: 14px;
  font-weight: 500;
  border-radius: 6px;
}

.demo-bar {
  margin-top: 14px;
  text-align: center;
  font-size: 12px;
  color: #6B7280;
  line-height: 1.8;
}

.demo-tip {
  color: #6B7280;
}

.demo-link {
  padding: 0 2px;
  border: none;
  background: none;
  color: #4B5563;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}

.demo-link:hover,
.demo-link.active {
  color: #2055A4;
  text-decoration: underline;
}

.demo-sep {
  margin: 0 6px;
  color: #D1D5DB;
}
</style>
