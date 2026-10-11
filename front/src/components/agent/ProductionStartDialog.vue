<template>
  <el-dialog v-model="visible" title="小说转视频准备" width="min(680px, calc(100vw - 32px))" append-to-body align-center :close-on-click-modal="false">
    <el-alert title="Agent 会自动拆解并保存分镜、整理文字资产、选择模式和参考素材。缺少的图片会列入交付清单。" type="info" :closable="false" class="mb-4" />
    <el-alert v-if="!available" title="请在 Agent 配置中选择模型并启用助手。" type="warning" :closable="false" class="mb-4" />
    <el-form label-position="top" :disabled="busy">
      <div class="grid grid-cols-2 gap-3">
        <el-form-item label="项目名称"><el-input v-model="form.dramaTitle" placeholder="为空时由 Agent 根据原文命名" maxlength="100" /></el-form-item>
        <el-form-item label="追加到已有项目（可选）"><el-select v-model="form.dramaId" clearable filterable class="w-full" placeholder="新建项目">
          <el-option v-for="p in projects" :key="String(p.id)" :value="String(p.id)" :label="p.title" />
        </el-select></el-form-item>
      </div>
      <el-form-item label="小说 / 剧本原文"><el-input v-model="form.sourceText" type="textarea" :rows="9" maxlength="100000" show-word-limit placeholder="粘贴需要制作的小说章节或剧本" /></el-form-item>
      <div class="grid grid-cols-2 gap-3">
        <el-form-item label="画幅"><el-select v-model="form.aspectRatio" class="w-full"><el-option v-for="r in ['9:16', '16:9', '1:1', '4:3']" :key="r" :value="r" :label="r" /></el-select></el-form-item>
        <el-form-item label="镜头节奏"><el-select v-model="form.pacingPreset" class="w-full"><el-option v-for="p in pacing" :key="p.value" :value="p.value" :label="p.label" /></el-select></el-form-item>
      </div>
      <el-form-item label="视觉风格"><el-input v-model="form.styleTone" placeholder="例如：温暖的手绘动画风格，柔和光影" maxlength="2000" /></el-form-item>
      <el-form-item label="制作要求"><el-input v-model="form.instruction" type="textarea" :rows="2" maxlength="4000" placeholder="保留原文对白、角色造型要求、镜头偏好等" /></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="visible = false">关闭</el-button><el-button type="primary" :loading="busy" :disabled="!available || !form.sourceText.trim()" @click="submit">开始转视频准备</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { agentApi } from '@/api/agent'
import { dramaApi } from '@/api/drama'
import { useAgentStore } from '@/store/agent'
import type { ProductionStart } from '@/types/agent'
const visible = defineModel<boolean>({ default: false })
const store = useAgentStore()
const busy = ref(false)
const projects = ref<{ id: string | number; title: string }[]>([])
const available = computed(() => store.profiles.length > 0)
const form = reactive<ProductionStart>({ clientRequestId: crypto.randomUUID(), sourceText: '', aspectRatio: '9:16', pacingPreset: 'AUTO' })
const pacing = [{value:'AUTO',label:'自动'}, {value:'FAST_PACED',label:'快速'}, {value:'COMPACT',label:'紧凑'}, {value:'STANDARD',label:'常规'}, {value:'CINEMATIC_LONG',label:'长镜头'}]
let submittedBody = ''
watch(visible, async show => { if (show) { await store.refreshProfiles(); projects.value = await dramaApi.getOptions() } })
async function submit() {
  if (busy.value || !form.sourceText.trim()) return
  busy.value = true
  try {
    const body = { ...form, dramaId: form.dramaId || undefined, sourceText: form.sourceText.trim() }
    const identity = JSON.stringify({ ...body, clientRequestId: undefined })
    if (submittedBody && submittedBody !== identity) form.clientRequestId = crypto.randomUUID()
    submittedBody = identity; body.clientRequestId = form.clientRequestId
    const result = await agentApi.startProduction(body)
    const session = await agentApi.session(result.sessionId)
    if (!store.sessions.some(s => s.id === session.id)) store.sessions.unshift(session)
    await store.selectSession(result.sessionId, result.runId)
    visible.value = false
    form.clientRequestId = crypto.randomUUID(); submittedBody = ''; form.sourceText = ''
  } finally { busy.value = false }
}
</script>
