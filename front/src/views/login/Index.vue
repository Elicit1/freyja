<template>
  <main class="login-page" :class="'login-theme-' + themeId">
    <div class="login-backdrop" aria-hidden="true" />
    <header class="login-header">
      <div class="studio-brand">
        <img src="/logo/logo.png" alt="FREYJA" class="brand-logo" />
        <div><strong>FREYJA</strong><span>AI DIGITAL STUDIO</span></div>
      </div>
      <el-button text class="appearance-button" :icon="Brush" @click="appStore.openThemePanel">片场外观</el-button>
    </header>

    <div class="login-stage">
      <section v-if="themeId !== 'starlight'" class="brand-message" aria-label="FREYJA 创作工作台">
        <div class="story-kicker"><span /> FREYJA CREATIVE WORKSPACE</div>
        <h1>从灵感出发，<br />让故事成为画面。</h1>
        <p>把剧本、角色与镜头汇聚在同一个片场，<br />专注创作，余下的交给 FREYJA。</p>
        <div class="capabilities">
          <span><el-icon><EditPen /></el-icon>剧本创作</span>
          <span><el-icon><Picture /></el-icon>视觉资产</span>
          <span><el-icon><Film /></el-icon>影像制作</span>
        </div>
        <div class="story-track" aria-hidden="true"><i /><i /><i /><i /><i /><i /><i /><i /></div>
      </section>

      <section class="login-card studio-card" aria-labelledby="login-heading">
        <div class="workspace-label"><span /> FREYJA 创作工作台</div>
        <h2 id="login-heading">欢迎回到片场</h2>
        <p class="login-subtitle">让灵感，成为你的下一部作品。</p>
        <el-alert v-if="route.query.passwordChanged === '1'" class="password-changed" title="密码已修改，请使用新密码登录" type="success" show-icon :closable="false" />
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" hide-required-asterisk @submit.prevent="submit">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" name="username" autocomplete="username" placeholder="请输入你的账号" :maxlength="64" :disabled="loading">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="登录密码" prop="password">
            <el-input v-model="form.password" name="password" type="password" autocomplete="current-password" show-password placeholder="请输入登录密码" :maxlength="256" :disabled="loading">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button class="login-button" type="primary" native-type="submit" :loading="loading">
            {{ loading ? '正在进入片场' : '进入工作台' }}<el-icon v-if="!loading"><Right /></el-icon>
          </el-button>
        </el-form>
        <div class="login-card-footer"><el-icon><Lock /></el-icon><span>安全登录 · 专注创作</span></div>
      </section>
    </div>

    <footer class="login-footer"><span>FREYJA AI DIGITAL STUDIO</span><span>你的故事，由此开始。</span></footer>
    <ThemePanel />
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { User, Lock, Right, Brush, EditPen, Picture, Film } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { useAppStore } from '@/store/app'
import { useAppearance } from '@/composables/useAppearance'
import ThemePanel from '@/components/theme/ThemePanel.vue'
import { saveLoginSession, safeLoginRedirect } from '@/utils/auth-session'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const { themeId } = useAppearance()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit() {
  if (loading.value) return
  loading.value = true
  try {
    if (!await formRef.value?.validate().catch(() => false)) return
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
.login-page { position: relative; isolation: isolate; min-height: 100dvh; display: flex; flex-direction: column; padding: 0 clamp(24px, 5vw, 80px); color: var(--text-primary); background: var(--app-bg); }
.login-backdrop { position: absolute; z-index: -1; inset: 0; pointer-events: none; background: radial-gradient(ellipse at 12% 40%, var(--brand-soft), transparent 58%); }
.login-theme-starlight .login-backdrop { background-image: linear-gradient(90deg, color-mix(in srgb, var(--app-bg) 8%, transparent), color-mix(in srgb, var(--app-bg) 15%, transparent)), url('/bg/bg.png'); background-position: left center; background-size: cover; background-repeat: no-repeat; }
html[data-color-mode="dark"] .login-theme-starlight .login-backdrop { background-image: linear-gradient(90deg, color-mix(in srgb, var(--app-bg) 58%, transparent), color-mix(in srgb, var(--app-bg) 68%, transparent)), url('/bg/bg.png'); }
.login-header { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 24px; min-height: 112px; }
.studio-brand { display: flex; align-items: center; gap: 12px; }
.brand-logo { width: 44px; height: 44px; object-fit: contain; }
.studio-brand strong { display: block; font-size: 19px; font-weight: 800; letter-spacing: .16em; line-height: 1.4; }
.studio-brand span { display: block; margin-top: 2px; color: var(--text-secondary); font-size: 9px; font-weight: 600; letter-spacing: .17em; }
.appearance-button { color: var(--text-secondary); background: var(--surface); border: 1px solid var(--border-default); backdrop-filter: blur(12px); }
.login-stage { display: grid; grid-template-columns: minmax(0, 1fr) 420px; align-items: center; gap: clamp(48px, 8vw, 120px); width: min(1160px, 100%); flex: 1; margin: 0 auto; padding: 36px 0 60px; }
.brand-message { padding: 28px 0; }
.story-kicker { display: flex; align-items: center; gap: 10px; color: var(--brand); font-size: 10px; font-weight: 600; letter-spacing: .16em; }
.story-kicker > span { height: 1px; width: 26px; background: var(--brand); }
h1 { margin: 28px 0 24px; font-size: clamp(32px, 3.4vw, 48px); font-weight: 650; line-height: 1.55; letter-spacing: -.03em; color: var(--text-primary); }
.brand-message > p { color: var(--text-secondary); font-size: 14px; line-height: 2; }
.capabilities { display: flex; align-items: center; gap: 24px; margin-top: 40px; }
.capabilities > span { display: inline-flex; align-items: center; gap: 8px; color: var(--text-secondary); font-size: 12px; white-space: nowrap; }
.capabilities .el-icon { color: var(--brand); font-size: 16px; }
.story-track { display: flex; align-items: flex-end; gap: 7px; height: 66px; margin-top: 44px; opacity: .75; }
.story-track > i { width: 32px; height: 28px; border: 1px solid color-mix(in srgb, var(--brand) 22%, transparent); border-radius: 5px; background: var(--brand-soft); }
.story-track > i:nth-child(3n) { height: 52px; }
.story-track > i:nth-child(3n + 1) { height: 40px; }
.login-card { grid-column: 2; padding: 38px 36px 0; border-radius: var(--radius-lg); box-shadow: var(--shadow-floating); overflow: hidden; backdrop-filter: blur(18px); -webkit-backdrop-filter: blur(18px); }
.login-card:hover { box-shadow: var(--shadow-floating); }
.workspace-label { display: flex; align-items: center; gap: 8px; color: var(--brand); font-size: 11px; font-weight: 500; margin-bottom: 24px; letter-spacing: .03em; }
.workspace-label > span { width: 6px; height: 6px; border-radius: 50%; background: var(--brand); box-shadow: 0 0 0 3px var(--brand-soft); }
h2 { margin: 0 0 10px; color: var(--text-primary); font-size: 27px; font-weight: 650; line-height: 1.4; letter-spacing: -.03em; }
.login-subtitle { color: var(--text-secondary); font-size: 13px; line-height: 1.7; margin-bottom: 30px; }
.password-changed { margin-bottom: 22px; }
.login-card :deep(.el-form-item) { margin-bottom: 24px; }
.login-card :deep(.el-form-item__label) { color: var(--text-primary); font-size: 12px; font-weight: 500; padding-bottom: 8px; line-height: 20px; }
.login-card :deep(.el-input__wrapper) { min-height: 46px; padding: 0 14px; background: var(--surface-muted); }
.login-card :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px var(--brand) inset, 0 0 0 3px var(--brand-soft); }
.login-card :deep(.is-error .el-input__wrapper) { box-shadow: 0 0 0 1px var(--danger) inset; }
.login-card :deep(.el-input__prefix) { color: var(--text-muted); margin-right: 4px; }
.login-card :deep(.el-input__inner) { font-size: 13px; }
.login-card :deep(input:-webkit-autofill) { -webkit-text-fill-color: var(--text-primary); -webkit-box-shadow: 0 0 0 50px var(--surface-muted) inset; }
.login-button { width: 100%; min-height: 46px; margin: 6px 0 32px; font-size: 14px; letter-spacing: .03em; }
.login-button :deep(.el-icon) { margin-left: 10px; }
.login-button:focus-visible { outline: 2px solid var(--brand); outline-offset: 4px; }
.login-card-footer { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 18px 0; border-top: 1px solid var(--border-default); color: var(--text-muted); font-size: 11px; }
.login-footer { display: flex; justify-content: space-between; align-items: center; gap: 16px; min-height: 64px; color: var(--text-muted); font-size: 10px; letter-spacing: .07em; }
@media (max-width: 900px) and (min-width: 761px) { .login-stage { grid-template-columns: minmax(0, 1fr) 380px; gap: 32px; } .capabilities { gap: 14px; } h1 { font-size: 30px; } }
@media (max-width: 760px) {
  .login-page { padding: 0 20px; }
  .login-header { min-height: 92px; gap: 12px; }
  .brand-logo { width: 36px; height: 36px; }
  .studio-brand { gap: 8px; }
  .studio-brand strong { font-size: 16px; }
  .studio-brand span { font-size: 7px; letter-spacing: .12em; }
  .appearance-button { padding: 8px 10px; font-size: 12px; }
  .login-stage { display: flex; justify-content: center; padding: 24px 0 40px; }
  .brand-message { display: none; }
  .login-card { width: min(420px, 100%); padding: 32px 26px 0; }
  .login-theme-starlight .login-backdrop { background-position: 24% center; }
  .login-theme-starlight .login-backdrop::after { content: ''; position: absolute; inset: 0; background: color-mix(in srgb, var(--app-bg) 48%, transparent); }
  .login-footer { font-size: 8px; letter-spacing: .03em; }
  h2 { font-size: 25px; }
}
@media (max-width: 360px) { .login-page { padding: 0 14px; } .login-card { padding: 28px 22px 0; } .appearance-button { font-size: 11px; } }
</style>
