<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../api'

const router = useRouter()

const mode = ref('login') // login | register
const formRef = ref(null)
const form = reactive({ username: '', password: '', captcha: '' })
const captchaImg = ref('')
const captchaUuid = ref('')
const loading = ref(false)

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度 3~20 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6~32 位', trigger: 'blur' },
  ],
  captcha: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
}

async function loadCaptcha() {
  try {
    const res = await authApi.captcha()
    captchaUuid.value = res.data.uuid
    captchaImg.value = res.data.img
    form.captcha = ''
  } catch {
    /* 忽略：提交时会再提示 */
  }
}

function onTabChange(name) {
  mode.value = name
  formRef.value?.clearValidate()
  loadCaptcha()
}

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    if (mode.value === 'login') {
      const res = await authApi.login({
        username: form.username,
        password: form.password,
        uuid: captchaUuid.value,
        captcha: form.captcha,
      })
      localStorage.setItem('token', res.data.token)
      localStorage.setItem('username', res.data.username ?? form.username)
      localStorage.setItem('admin', res.data.admin ? '1' : '0')
      window.dispatchEvent(new Event('auth-changed'))
      ElMessage.success('登录成功')
      router.push('/')
    } else {
      await authApi.register({
        username: form.username,
        password: form.password,
        uuid: captchaUuid.value,
        captcha: form.captcha,
      })
      ElMessage.success('注册成功，请登录')
      mode.value = 'login'
      formRef.value?.clearValidate()
      loadCaptcha()
    }
  } catch (e) {
    ElMessage.error(
      e.response?.data?.message ||
        (mode.value === 'login' ? '登录失败，请重试' : '注册失败，请重试')
    )
    loadCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(loadCaptcha)
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-title">🎯 面试模拟器</div>
      <div class="login-sub">简历 → AI 出题 → 要点判分 → 错题讲解</div>

      <el-tabs v-model="mode" class="login-tabs" stretch @tab-change="onTabChange">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large"
        @submit.prevent="submit">
        <el-form-item prop="username">
          <el-input v-model.trim="form.username" placeholder="用户名" clearable autocomplete="username" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model.trim="form.password" type="password" placeholder="密码（至少 6 位）"
            show-password autocomplete="current-password" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item prop="captcha">
          <div class="captcha-row">
            <el-input v-model.trim="form.captcha" placeholder="验证码" maxlength="6" @keyup.enter="submit" />
            <img v-if="captchaImg" :src="captchaImg" class="captcha-img" title="点击刷新" @click="loadCaptcha" />
          </div>
        </el-form-item>
        <el-button type="primary" class="login-btn" size="large" :loading="loading" native-type="submit">
          {{ mode === 'login' ? '登 录' : '注 册' }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  overflow: hidden;
  background: var(--c-bg);
}
.login-page::before {
  content: '';
  position: absolute;
  top: -440px;
  left: 50%;
  width: 920px;
  height: 920px;
  transform: translateX(-50%);
  background: radial-gradient(circle at center, rgba(10, 132, 255, 0.24), rgba(10, 132, 255, 0) 62%);
  pointer-events: none;
}
.login-page::after {
  content: '';
  position: absolute;
  right: -180px;
  bottom: -360px;
  width: 640px;
  height: 640px;
  background: radial-gradient(circle at center, rgba(124, 77, 255, 0.18), rgba(124, 77, 255, 0) 62%);
  pointer-events: none;
}
.login-card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 440px;
  padding: 56px 44px 44px;
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: saturate(180%) blur(30px);
  -webkit-backdrop-filter: saturate(180%) blur(30px);
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-3);
}
.login-title {
  font-size: clamp(30px, 5vw, 40px);
  font-weight: 700;
  letter-spacing: -0.04em;
  color: var(--c-text);
  text-align: center;
}
.login-sub {
  font-size: 16px;
  color: var(--c-text-3);
  text-align: center;
  margin: 12px 0 6px;
}
.login-tabs {
  margin-top: 28px;
}
.login-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}
.login-tabs :deep(.el-tabs__nav) {
  width: 100%;
  padding: 4px;
  background: #f0f0f2;
  border-radius: var(--radius-pill);
}
.login-tabs :deep(.el-tabs__item) {
  height: 38px;
  line-height: 38px;
  border-radius: var(--radius-pill);
  font-size: 15px;
  transition: background 0.2s ease, box-shadow 0.2s ease;
}
.login-tabs :deep(.el-tabs__item.is-active) {
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
}
.login-tabs :deep(.el-tabs__active-bar) {
  display: none;
}
.login-btn {
  width: 100%;
  height: 54px;
  margin-top: 12px;
  font-size: 17px;
  font-weight: 600;
}
.captcha-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}
.captcha-row :deep(.el-input) {
  flex: 1;
  min-width: 0;
}
.captcha-img {
  flex: 0 0 auto;
  height: 44px;
  width: 130px;
  border-radius: var(--radius-md);
  border: 1px solid var(--c-line);
  cursor: pointer;
  background: #fff;
}
@media (max-width: 480px) {
  .login-page { padding: 40px 16px; }
  .login-card { padding: 36px 22px 30px; }
  .login-title { font-size: 26px; }
  .captcha-img { width: 108px; height: 40px; }
}
</style>