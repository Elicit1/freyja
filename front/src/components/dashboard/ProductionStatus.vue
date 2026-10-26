<template>
  <aside class="production-summary">
    <section class="studio-card summary-card">
      <h3><el-icon><VideoCamera /></el-icon> 镜头制作进度</h3>
      <div v-for="item in coverage" :key="item.label" class="coverage-row">
        <div class="coverage-label"><span>{{ item.label }}</span><strong>{{ item.value }} <small>/ {{ shotCount }} 镜</small></strong></div>
        <el-progress :percentage="completionPercent(item.value, shotCount)" :stroke-width="7" :show-text="false" :color="item.color" />
        <p>{{ shotCount > 0 ? completionPercent(item.value, shotCount) + '% 已完成' : '创建分镜后查看进度' }}</p>
      </div>
    </section>
    <section class="studio-card summary-card">
      <div class="summary-heading"><h3><el-icon><Cpu /></el-icon> AI 服务配置</h3><router-link to="/system/ai-provider">管理 <el-icon><ArrowRight /></el-icon></router-link></div>
      <div class="service-status"><span class="service-dot" :class="{ ready: aiReady }" /><strong>{{ aiReady ? '已配置 AI 服务' : '待配置 AI 服务' }}</strong><span>{{ aiProviderCount }} 个提供商</span></div>
      <p class="summary-note">{{ aiReady ? '使用已配置的模型推进剧本与画面制作。' : '配置提供商和模型后，即可开始 AI 创作。' }}</p>
    </section>
    <section class="studio-card summary-card">
      <div class="summary-heading"><h3><el-icon><Loading /></el-icon> 制作任务</h3><router-link to="/render/tasks">查看全部 <el-icon><ArrowRight /></el-icon></router-link></div>
      <div v-if="!tasks.length" class="no-tasks"><el-icon><CircleCheck /></el-icon><span>当前没有进行中的制作任务</span></div>
      <div v-for="task in tasks.slice(0, 2)" :key="task.sourceType + ':' + task.taskId" class="summary-task">
        <div><strong>{{ task.title }}</strong><span>{{ task.currentStage || '处理中' }}</span></div>
        <el-progress v-if="task.progress != null" :percentage="Math.max(0, Math.min(100, task.progress))" :stroke-width="5" :show-text="false" />
      </div>
      <p v-if="tasks.length" class="summary-note">{{ tasks.length }} 项制作任务进行中</p>
    </section>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Cpu, VideoCamera, Loading, ArrowRight, CircleCheck } from '@element-plus/icons-vue'
import type { TaskCenterItem } from '@/api/task-center'
import { completionPercent } from '@/utils/project-display'
const props = defineProps<{ aiReady: boolean; aiProviderCount: number; shotCount: number; renderedVideoCount: number; renderedImageCount: number; tasks: TaskCenterItem[] }>()
const coverage = computed(() => [
  { label: '成片视频', value: props.renderedVideoCount, color: 'var(--brand)' },
  { label: '首帧画面', value: props.renderedImageCount, color: 'var(--success)' }
])
</script>

<style scoped>
.production-summary { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.summary-card { padding: 16px; }
h3 { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 650; }
h3 .el-icon { color: var(--brand); }
.summary-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.summary-heading a { display: flex; align-items: center; gap: 4px; color: var(--brand); font-size: 11px; white-space: nowrap; }
.coverage-row { margin-top: 14px; }
.coverage-label { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 10px; font-size: 12px; color: var(--text-secondary); }
.coverage-label strong { color: var(--text-primary); font-variant-numeric: tabular-nums; }
.coverage-label small { color: var(--text-muted); font-size: 11px; font-weight: 400; }
.coverage-row p, .summary-note { margin-top: 6px; font-size: 11px; color: var(--text-muted); line-height: 1.7; }
.service-status { display: flex; align-items: center; gap: 8px; margin-top: 14px; font-size: 12px; }
.service-status > span:last-child { margin-left: auto; color: var(--text-secondary); }
.service-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--warning); }
.service-dot.ready { background: var(--success); }
.no-tasks { display: flex; align-items: center; gap: 8px; padding: 16px 0 4px; color: var(--text-muted); font-size: 12px; }
.summary-task { padding: 12px; margin-top: 12px; background: var(--surface-muted); border-radius: 8px; }
.summary-task > div:first-child { display: flex; flex-direction: column; gap: 6px; margin-bottom: 8px; font-size: 12px; }
.summary-task strong { overflow-wrap: anywhere; font-weight: 500; }
.summary-task span { font-size: 11px; color: var(--text-muted); }
</style>
