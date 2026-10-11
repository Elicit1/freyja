<template>
  <div v-if="run" :id="`agent-run-${run.id}`" class="run-card">
    <div class="flex justify-between items-center gap-2">
      <strong>{{ agentStatusLabels[run.status] || run.status }}</strong>
      <el-tag size="small" :type="run.status === 'FAILED' ? 'danger' : run.status === 'COMPLETED' ? 'success' : 'info'">配置 v{{ run.configRevision }}</el-tag>
    </div>
    <p v-if="run.result?.summary" class="whitespace-pre-wrap mt-2">{{ run.result.summary }}</p>
    <p v-if="run.errorMessage" class="text-[var(--danger)] mt-2">{{ run.errorMessage }}</p>
    <div class="text-xs text-[var(--text-secondary)] mt-3">{{ run.modelTurns }} 轮模型 · {{ run.toolCalls }} 次工具
      <span v-if="run.usageUnknown"> · Token 用量不完整</span>
      <span v-else> · {{ (run.promptTokens || 0) + (run.completionTokens || 0) }} Tokens</span>
    </div>
    <el-button v-if="run.status === 'FAILED'" size="small" class="mt-3" @click="resume">重试此任务</el-button>
    <ProductionReportCard :run="run" />
  </div>
</template>
<script setup lang="ts">
import { agentApi } from '@/api/agent'
import { agentStatusLabels, type AgentRun } from '@/types/agent'
import ProductionReportCard from './ProductionReportCard.vue'
const props = defineProps<{ run?: AgentRun }>()
const emit = defineEmits<{ refreshed: [] }>()
async function resume() { if (props.run) { await agentApi.resume(props.run.id); emit('refreshed') } }
</script>
<style scoped>
.run-card { padding: 14px; border-radius: 12px; border: 1px solid var(--border-default); background: var(--surface-muted); font-size: 13px; }
</style>
