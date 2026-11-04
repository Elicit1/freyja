<template>
  <el-dialog :model-value="modelValue" title="修改登录密码" width="min(460px, calc(100vw - 32px))" append-to-body
    :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving"
    @update:model-value="emit('update:modelValue', $event)" @closed="reset">
    <p class="password-note">设置新的登录密码，下次启动后依然有效。修改成功后，所有已登录设备都需要重新登录。</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="当前密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" name="current-password" autocomplete="current-password" show-password :maxlength="256" :disabled="saving" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" name="new-password" autocomplete="new-password" show-password placeholder="12～256 个字符" :maxlength="256" :disabled="saving" />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" name="confirm-password" autocomplete="new-password" show-password :maxlength="256" :disabled="saving" />
      </el-form-item>
      <div class="password-actions">
        <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" native-type="submit" :loading="saving">保存并重新登录</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { authApi } from '@/api/auth'
import { clearLoginSession } from '@/utils/auth-session'

defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const formRef = ref<FormInstance>()
const saving = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [{ validator: (_rule, value: string, callback) => {
    if (!value || !value.trim() || value.length < 12 || value.length > 256) callback(new Error('新密码需要 12～256 个字符，且不能全为空白'))
    else if (value === form.oldPassword) callback(new Error('新密码不能与当前密码相同'))
    else callback()
  }, trigger: 'blur' }],
  confirmPassword: [{ validator: (_rule, value: string, callback) => {
    if (!value || value !== form.newPassword) callback(new Error('两次输入的新密码不一致'))
    else callback()
  }, trigger: 'blur' }]
}

function reset() {
  Object.assign(form, { oldPassword: '', newPassword: '', confirmPassword: '' })
  formRef.value?.clearValidate()
}

async function submit() {
  if (saving.value) return
  saving.value = true
  try {
    if (!await formRef.value?.validate().catch(() => false)) return
    await authApi.changePassword(form.oldPassword, form.newPassword)
    reset()
    clearLoginSession()
    window.location.replace('/login?passwordChanged=1')
  } catch {
    // The request interceptor presents the server's validation error.
  } finally { saving.value = false }
}
</script>

<style scoped>
.password-note { color: var(--text-secondary); font-size: 13px; line-height: 1.8; margin: 0 0 24px; }
.password-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 24px; }
</style>
