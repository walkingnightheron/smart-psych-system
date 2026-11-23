<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/api/request'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const form = reactive({
  username: '',
  password: ''
})

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const res = await request.post('/auth/login', form)
    userStore.setToken(res.data?.token ?? '')
    userStore.profile = res.data?.user ?? null
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <h1 class="login-title">智慧心理健康评估系统</h1>
      <p class="login-subtitle">管理端 · 学生心理问卷评估</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="学号 / 工号" size="large" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
          />
        </el-form-item>
        <el-button type="primary" size="large" class="login-button" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>
      <p class="login-hint">骨架阶段：登录接口 /api/auth/login 待按 REQ01 实现</p>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: linear-gradient(135deg, #1f2d3d 0%, #2f4b6e 100%);
}

.login-card {
  width: 400px;
  padding: 12px;
}

.login-title {
  margin: 0 0 8px;
  font-size: 22px;
  text-align: center;
}

.login-subtitle {
  margin: 0 0 28px;
  color: #909399;
  text-align: center;
}

.login-button {
  width: 100%;
}

.login-hint {
  margin: 20px 0 0;
  color: #c0c4cc;
  font-size: 12px;
  text-align: center;
}
</style>
