import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const profile = ref(null)

  const displayName = computed(() => profile.value?.name || '未登录')

  function setToken(value) {
    token.value = value
    localStorage.setItem('token', value)
  }

  function clear() {
    token.value = ''
    profile.value = null
    localStorage.removeItem('token')
  }

  return { token, profile, displayName, setToken, clear }
})
