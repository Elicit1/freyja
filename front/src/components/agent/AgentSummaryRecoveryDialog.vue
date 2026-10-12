<template>
  <el-dialog v-model="visible" title="调整摘要配置并继续" width="min(660px, calc(100vw - 32px))" append-to-body align-center>
    <el-alert title="继续当前任务，保留已完成步骤、历史消息与已使用预算，仅更新摘要调用配置。" type="info" :closable="false" class="mb-4" />
    <p v-if="run.errorMessage" class="text-sm mb-4 text-[var(--text-secondary)]">{{ run.errorMessage }}</p>
    <AgentSummarySettings v-if="summary" v-model="summary" :providers="providers" :main-provider-id="run.modelProviderId" :main-model-code="run.modelCode" />
    <template #footer><el-button :disabled="busy" @click="visible = false">取消</el-button><el-button type="primary" :loading="busy" @click="resume">应用并继续当前任务</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { agentApi } from '@/api/agent'
import { aiProviderApi } from '@/api/ai-provider'
import type { AgentRun, AgentSummaryConfig } from '@/types/agent'
import type { AiProviderVO } from '@/types/ai-provider'
import AgentSummarySettings from './AgentSummarySettings.vue'
const visible = defineModel<boolean>({ default: false })
const props = defineProps<{ run: AgentRun }>()
const emit = defineEmits<{ refreshed: [] }>()
const summary = ref<AgentSummaryConfig>()
const providers = ref<AiProviderVO[]>([])
const busy = ref(false)
watch(visible, async show => {
  if (!show) return
  summary.value = JSON.parse(JSON.stringify(props.run.summaryConfig || { maxTokens: 16384, retryMaxTokens: 32768, reasoningMode: 'DISABLED', timeoutSeconds: 180, targetCharacters: 2500, maxCharacters: 4000, maxRetries: 2 }))
  providers.value = await aiProviderApi.getListEnabled()
})
async function resume() {
  if (!summary.value || busy.value) return
  busy.value = true
  try { await agentApi.resumeSummary(props.run.id, summary.value); visible.value = false; emit('refreshed'); ElMessage.success('摘要配置已更新，正在继续当前任务') }
  finally { busy.value = false }
}
</script>
