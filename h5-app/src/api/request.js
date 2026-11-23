const BASE_URL = import.meta.env.VITE_API_BASE || '/api'

let isRedirecting401 = false

function navigateToLogin() {
  if (isRedirecting401) return
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  const currentRoute = pages.length ? pages[pages.length - 1]?.route : ''
  if (currentRoute === 'pages/login/login') return

  isRedirecting401 = true
  setTimeout(() => {
    uni.reLaunch({
      url: '/pages/login/login',
      complete: () => {
        isRedirecting401 = false
      }
    })
  }, 150)
}

export function request({
  url,
  method = 'GET',
  data,
  header = {},
  skipErrorToast = false,
  skipAuthRedirect = false
}) {
  const token = uni.getStorageSync('token')

  return new Promise((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}${url}`,
      method,
      data,
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...header
      },
      success: (res) => {
        const statusCode = res.statusCode
        const body = res.data && typeof res.data === 'object' ? res.data : {}

        if (statusCode >= 200 && statusCode < 300) {
          if (typeof body.code === 'number' && body.code !== 0) {
            if (body.code === 401) {
              uni.removeStorageSync('token')
              uni.removeStorageSync('user')
              if (!skipErrorToast) {
                uni.showToast({ title: '请先登录', icon: 'none' })
              }
              if (!skipAuthRedirect) {
                navigateToLogin()
              }
              reject({ statusCode: 401, ...body })
              return
            }
            if (body.code === 409) {
              if (!skipErrorToast) {
                uni.showToast({
                  title: body.message || '您已提交过本次评估答卷，请勿重复提交',
                  icon: 'none'
                })
              }
              reject({
                statusCode: 409,
                code: 409,
                message: body.message || '您已提交过本次评估答卷，请勿重复提交',
                data: body.data
              })
              return
            }
            if (!skipErrorToast) {
              uni.showToast({ title: body.message || '请求失败', icon: 'none' })
            }
            reject({ statusCode: body.code || statusCode, ...body })
            return
          }
          resolve(body)
          return
        }

        if (statusCode === 401) {
          uni.removeStorageSync('token')
          uni.removeStorageSync('user')
          if (!skipErrorToast) {
            uni.showToast({ title: '请先登录', icon: 'none' })
          }
          if (!skipAuthRedirect) {
            navigateToLogin()
          }
          reject({ statusCode: 401, ...body })
          return
        }

        if (statusCode === 403) {
          if (!skipErrorToast) {
            uni.showToast({
              title: body.message || '无权访问该资源',
              icon: 'none'
            })
          }
          reject({ statusCode: 403, ...body })
          return
        }

        if (statusCode === 409) {
          if (!skipErrorToast) {
            uni.showToast({
              title: body.message || '您已提交过本次评估答卷，请勿重复提交',
              icon: 'none'
            })
          }
          reject({
            statusCode: 409,
            code: 409,
            message: body.message || '您已提交过本次评估答卷，请勿重复提交',
            data: body.data
          })
          return
        }

        if (!skipErrorToast) {
          uni.showToast({
            title: body.message || '请求失败',
            icon: 'none'
          })
        }
        reject({ statusCode, ...body })
      },
      fail: (error) => {
        if (!skipErrorToast) {
          uni.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
        }
        reject({
          statusCode: 0,
          message: '网络异常，请稍后重试',
          error
        })
      }
    })
  })
}

