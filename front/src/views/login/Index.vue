<template>
  <main class="login-page">
    <section class="login-card">
      <div class="brand-mark">F</div>
      <p class="eyebrow">FREYJA AI DIGITAL STUDIO</p>
      <h1>登录创作片场</h1>
      <p class="intro">从故事到画面，继续你的创作。</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" name="username" autocomplete="username" placeholder="请输入用户名" :maxlength="64" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" name="password" type="password" autocomplete="current-password" show-password placeholder="请输入密码" :maxlength="256" />
        </el-form-item>
        <el-button class="login-button" type="primary" native-type="submit" :loading="loading">进入工作台</el-button>
      </el-form>
      <p class="login-help">管理员账户由部署配置提供。首次启动未设置密码时，请查看后端启动日志。</p>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { authApi } from '@/api/auth'
import { saveLoginSession, safeLoginRedirect } from '@/utils/auth-session'

const route = useRoute()
const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit() {
  if (loading.value || !await formRef.value?.validate().catch(() => false)) return
  loading.value = true
  try {
    const session = await authApi.login(form.username, form.password)
    saveLoginSession(session)
    form.password = ''
    await router.replace(safeLoginRedirect(route.query.redirect))
  } catch {
    // The shared request interceptor displays authentication errors.
  } finally { loading.value = false }
}
</script>

<style scoped>
.login-page { min-height: 100dvh; display: grid; place-items: center; padding: 24px; background: radial-gradient(ellipse at 15% 15%, color-mix(in srgb, var(--brand) 15%, transparent), transparent 55%), var(--app-bg); color: var(--text-primary); }
.login-card { width: min(100%, 440px); padding: 40px; border: 1px solid var(--border-default); border-radius: 24px; background: var(--surface-card, var(--app-bg)); box-shadow: 0 24px 80px #00000018; }
.brand-mark { width: 48px; height: 48px; display: grid; place-items: center; border-radius: 14px; background: var(--brand); color: white; font-size: 28px; font-weight: 800; margin-bottom: 24px; }
.eyebrow { color: var(--text-muted); font-size: 11px; letter-spacing: 2px; margin-bottom: 12px; }
h1 { font-size: 28px; font-weight: 700; margin-bottom: 10px; }
.intro { color: var(--text-secondary); margin-bottom: 30px; }
.login-button { width: 100%; margin-top: 8px; }
.login-help { font-size: 12px; color: var(--text-muted); line-height: 1.8; margin-top: 24px; }
@media (max-width: 480px) { .login-card { padding: 28px; } }
</style>
