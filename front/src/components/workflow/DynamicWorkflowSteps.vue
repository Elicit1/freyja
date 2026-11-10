<template>
  <section class="workflow-steps" v-loading="loading">
    <div class="flex justify-between items-center"><strong>动态工作流步骤</strong><el-button link @click="load">刷新</el-button></div>
    <p v-if="error" role="alert">{{ error }}</p><p v-else-if="!steps.length">此任务尚无独立增强步骤。</p>
    <div v-for="step in steps" :key="step.step_key" class="workflow-step"><div><strong>{{ labels[step.stage] || step.stage }}</strong><small>{{ step.step_key }}</small></div><span>{{ statuses[step.status] || step.status }}</span><el-button v-if="step.output_url" link type="primary" @click="preview = step.output_url">预览产物</el-button><p v-if="step.error_message">{{ step.error_message }}</p></div>
    <el-dialog :model-value="!!preview" title="步骤产物" width="min(900px,90vw)" append-to-body @close="preview = ''"><video v-if="preview" :src="preview" controls class="w-full max-h-[70vh]" /></el-dialog>
  </section>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { dynamicWorkflowApi } from '@/api/dynamic-workflow'
const props = defineProps<{ taskId: string; status?: string }>()
const steps = ref<Awaited<ReturnType<typeof dynamicWorkflowApi.steps>>>([]), loading = ref(false), error = ref(''), preview = ref('')
const labels: Record<string, string> = { BASE: '基础生成', UPSCALE: '二采放大', FACE: '人脸修复' }
const statuses: Record<string, string> = { PREPARING: '准备中', SUBMITTING: '执行或等待确认', SUCCESS: '已完成', FAILED: '失败' }
let revision = 0
async function load() { const token = ++revision; loading.value = true; error.value = ''; try { const result = await dynamicWorkflowApi.steps(props.taskId); if (token === revision) steps.value = result } catch (e: any) { if (token === revision) error.value = e.message || '读取步骤失败' } finally { if (token === revision) loading.value = false } }
watch(() => [props.taskId, props.status], load, { immediate: true })
</script>
<style scoped>
.workflow-steps{padding:16px;background:var(--surface-muted);border-radius:var(--radius-sm);font-size:12px;color:var(--text-primary)}.workflow-step{display:flex;align-items:center;flex-wrap:wrap;gap:16px;padding:12px 0;border-bottom:1px solid var(--border-default)}.workflow-step>div{flex:1;min-width:160px}.workflow-step small{display:block;color:var(--text-secondary);margin-top:4px}.workflow-step p{width:100%;margin:0;color:var(--text-secondary)}
</style>
