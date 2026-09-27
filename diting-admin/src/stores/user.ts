import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { LoginResult } from '@/types/api'
import { clearAuth, getNickname, getRole, getToken, saveAuth } from '@/utils/authToken'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(getToken())
  const role = ref<string>(getRole())
  const nickname = ref<string>(getNickname())

  function setLogin(result: LoginResult) {
    token.value = result.token
    role.value = result.role
    nickname.value = result.nickname
    saveAuth(result.token, result.role, result.nickname)
  }

  function clear() {
    token.value = ''
    role.value = ''
    nickname.value = ''
    clearAuth()
  }

  return { token, role, nickname, setLogin, clear }
})
