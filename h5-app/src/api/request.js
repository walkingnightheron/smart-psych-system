const BASE_URL = import.meta.env.VITE_API_BASE || '/api'

export function request({ url, method = 'GET', data, header = {} }) {
  const token = uni.getStorageSync('token')
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}${url}`,
      method,
      data,
      header: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...header
      },
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data)
          return
        }
        if (res.statusCode === 401) {
          uni.removeStorageSync('token')
        }
        uni.showToast({
          title: res.data?.message || `请求失败（${res.statusCode}）`,
          icon: 'none'
        })
        reject(res)
      },
      fail: (error) => {
        uni.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
        reject(error)
      }
    })
  })
}
