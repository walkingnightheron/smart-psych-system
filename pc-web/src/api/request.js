import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import router from '@/router'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000
})

let isHandling401 = false

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function handleUnauthorized() {
  try {
    const userStore = useUserStore()
    userStore.clear()
  } catch {
    localStorage.removeItem('token')
    localStorage.removeItem('psych_pc_profile')
  }

  if (!isHandling401) {
    isHandling401 = true
    ElMessage.error('未登录或登录已过期，请重新登录')
    setTimeout(() => {
      isHandling401 = false
    }, 1200)
  }

  if (router.currentRoute.value.path !== '/login') {
    router.replace('/login')
  }
}

request.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      if (body.code === 401) {
        handleUnauthorized()
      } else if (body.code === 403) {
        ElMessage.error(body.message || '权限不足，拒绝访问受限数据')
      } else {
        ElMessage.error(body.message || '请求失败')
      }
      const err = new Error(body.message || '请求失败')
      err.response = response
      return Promise.reject(err)
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    const serverMessage = error.response?.data?.message
    if (status === 401) {
      handleUnauthorized()
    } else if (status === 403) {
      ElMessage.error(serverMessage || '权限不足，拒绝访问受限数据')
    } else if (!error.config?.silentError) {
      ElMessage.error(serverMessage || '请求失败')
    }
    return Promise.reject(error)
  }
)

export default request
