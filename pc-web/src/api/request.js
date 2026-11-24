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
    if (response.config?.responseType === 'blob') {
      return response
    }
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      if (body.code === 401) {
        handleUnauthorized()
      } else if (body.code === 403) {
        ElMessage.error(body.message || '权限不足，拒绝访问受限数据')
      } else if (!response.config?.silentError) {
        ElMessage.error(body.message || '请求失败')
      }
      const err = new Error(body.message || '请求失败')
      err.response = response
      err.data = body
      return Promise.reject(err)
    }
    return body
  },
  async (error) => {
    if (error.response?.data instanceof Blob) {
      try {
        const text = await error.response.data.text()
        const parsed = JSON.parse(text)
        error.response.data = parsed
      } catch {
        // 非 JSON 文本则保持原样
      }
    }

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

export function extractImportErrors(error) {
  const list =
    error?.response?.data?.data?.errors ??
    error?.data?.data?.errors ??
    error?.response?.data?.errors ??
    []
  return Array.isArray(list) ? list : []
}

function resolveDownloadFilename(contentDisposition, defaultFilename) {
  if (!contentDisposition || typeof contentDisposition !== 'string') {
    return defaultFilename
  }
  const utf8Match = contentDisposition.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)
  if (utf8Match && utf8Match[1]) {
    try {
      return decodeURIComponent(utf8Match[1].trim())
    } catch {
      return utf8Match[1].trim()
    }
  }
  const plainMatch = contentDisposition.match(/filename\s*=\s*"?([^";]+)"?/i)
  if (plainMatch && plainMatch[1]) {
    try {
      return decodeURIComponent(plainMatch[1].trim())
    } catch {
      return plainMatch[1].trim()
    }
  }
  return defaultFilename
}

export async function downloadBlob(url, defaultFilename = 'download.xlsx', config = {}) {
  const response = await request.get(url, {
    ...config,
    responseType: 'blob'
  })

  const blob = response?.data
  if (!(blob instanceof Blob)) {
    throw new Error('下载响应格式异常')
  }

  if (blob.type && blob.type.toLowerCase().includes('application/json')) {
    let errMsg = '文件下载失败'
    try {
      const text = await blob.text()
      const json = JSON.parse(text)
      errMsg = json?.message || errMsg
      if (json?.code === 401) {
        handleUnauthorized()
      } else {
        ElMessage.error(errMsg)
      }
    } catch {
      ElMessage.error(errMsg)
    }
    throw new Error(errMsg)
  }

  const disposition =
    response.headers?.['content-disposition'] || response.headers?.['Content-Disposition']
  const filename = resolveDownloadFilename(disposition, defaultFilename)

  const objectUrl = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(objectUrl)
  return filename
}

export default request
