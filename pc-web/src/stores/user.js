import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getMeApi, updateProfileApi } from '@/api/admin'

const PROFILE_STORAGE_KEY = 'psych_pc_profile'

function loadCachedProfile() {
  try {
    const raw = localStorage.getItem(PROFILE_STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const profile = ref(loadCachedProfile())

  const displayName = computed(() => profile.value?.realName || profile.value?.username || '未登录')
  const isAdmin = computed(() => profile.value?.role === 'ADMIN')

  function setToken(value) {
    token.value = value || ''
    if (value) {
      localStorage.setItem('token', value)
    } else {
      localStorage.removeItem('token')
    }
  }

  function setProfile(userObj) {
    profile.value = userObj || null
    if (userObj) {
      localStorage.setItem(PROFILE_STORAGE_KEY, JSON.stringify(userObj))
    } else {
      localStorage.removeItem(PROFILE_STORAGE_KEY)
    }
  }

  async function fetchMe(config = {}) {
    const res = await getMeApi(config)
    const userObj = res?.data ?? res
    setProfile(userObj)
    return userObj
  }

  async function updateProfile(payload) {
    const res = await updateProfileApi(payload)
    const updatedUser = res?.data ?? res
    setProfile(updatedUser)
    return updatedUser
  }

  function clear() {
    token.value = ''
    profile.value = null
    localStorage.removeItem('token')
    localStorage.removeItem(PROFILE_STORAGE_KEY)
  }

  return {
    token,
    profile,
    displayName,
    isAdmin,
    setToken,
    setProfile,
    fetchMe,
    updateProfile,
    clear
  }
})
