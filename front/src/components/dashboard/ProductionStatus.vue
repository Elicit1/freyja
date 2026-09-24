<template>
  <div class="space-y-4">
    <!-- 片场引擎与服务状态 -->
    <div class="studio-card p-4">
      <div class="flex items-center justify-between pb-3 border-b border-[var(--border-default)] mb-3">
        <div class="flex items-center gap-2">
          <el-icon class="text-base text-[var(--brand)]"><Cpu /></el-icon>
          <span class="font-bold text-sm text-[var(--text-primary)]">片场引擎状态</span>
        </div>
        <span
          class="studio-badge"
          :class="aiReady ? 'bg-emerald-50 text-emerald-700 border border-emerald-200' : 'bg-amber-50 text-amber-700 border border-amber-200'"
        >
          <span class="w-1.5 h-1.5 rounded-full" :class="aiReady ? 'bg-emerald-500' : 'bg-amber-500'"></span>
          {{ aiReady ? '全链路就绪' : '部分配置待补全' }}
        </span>
      </div>

      <div class="space-y-2.5 text-xs">
        <!-- AI 算力与模型 -->
        <div class="flex items-center justify-between p-2 rounded-lg bg-[var(--surface-muted)]">
          <div class="flex items-center gap-2 text-[var(--text-secondary)]">
            <span class="w-2 h-2 rounded-full" :class="aiReady ? 'bg-[var(--success)]' : 'bg-[var(--warning)]'"></span>
            <span>AI 大模型与生图接入</span>
          </div>
          <span class="font-mono font-medium text-[var(--text-primary)]">
            {{ aiReady ? `${aiProviderCount} 个提供商启用` : '未配置模型' }}
          </span>
        </div>

        <!-- 调度渲染网关 -->
        <div class="flex items-center justify-between p-2 rounded-lg bg-[var(--surface-muted)]">
          <div class="flex items-center gap-2 text-[var(--text-secondary)]">
            <span class="w-2 h-2 rounded-full bg-[var(--success)]"></span>
            <span>FastAPI 渲染调度网关</span>
          </div>
          <span class="font-mono font-medium text-[var(--text-primary)]">
            {{ activeRenderCount > 0 ? `处理中 (${activeRenderCount})` : '待命中' }}
          </span>
        </div>
      </div>
    </div>

    <!-- 工业化创作 4 步流转 -->
    <div class="studio-card p-4">
      <div class="flex items-center justify-between pb-3 border-b border-[var(--border-default)] mb-3">
        <div class="flex items-center gap-2">
          <el-icon class="text-base text-[var(--brand)]"><Operation /></el-icon>
          <span class="font-bold text-sm text-[var(--text-primary)]">AI 工业化制片链路</span>
        </div>
        <span class="text-[11px] text-[var(--text-muted)]">标准流程</span>
      </div>

      <div class="space-y-2.5">
        <div
          v-for="(step, index) in steps"
          :key="step.title"
          class="flex items-start gap-3 p-2.5 rounded-lg border border-[var(--border-default)] bg-[var(--surface-muted)]"
        >
          <div class="w-5 h-5 rounded-full bg-[var(--brand-soft)] text-[var(--brand)] flex items-center justify-center font-bold text-xs shrink-0 mt-0.5 font-mono">
            {{ index + 1 }}
          </div>
          <div class="min-w-0">
            <div class="text-xs font-bold text-[var(--text-primary)]">{{ step.title }}</div>
            <div class="text-[11px] text-[var(--text-muted)] mt-0.5 leading-snug">{{ step.desc }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 常用导航直达 -->
    <div class="grid grid-cols-2 gap-2.5">
      <router-link
        to="/assets"
        class="p-3 rounded-xl border border-[var(--border-default)] bg-[var(--surface)] hover:border-[var(--brand)] hover:bg-[var(--surface-hover)] transition-all flex items-center gap-2.5 group"
      >
        <div class="w-8 h-8 rounded-lg bg-amber-50 text-amber-600 flex items-center justify-center shrink-0">
          <el-icon class="text-base"><PictureFilled /></el-icon>
        </div>
        <div class="min-w-0">
          <div class="text-xs font-bold text-[var(--text-primary)] group-hover:text-[var(--brand)] transition-colors truncate">资产中心</div>
          <div class="text-[10px] text-[var(--text-muted)] truncate">角色 · 场景 · 道具</div>
        </div>
      </router-link>

      <router-link
        to="/system/ai-provider"
        class="p-3 rounded-xl border border-[var(--border-default)] bg-[var(--surface)] hover:border-[var(--brand)] hover:bg-[var(--surface-hover)] transition-all flex items-center gap-2.5 group"
      >
        <div class="w-8 h-8 rounded-lg bg-purple-50 text-purple-600 flex items-center justify-center shrink-0">
          <el-icon class="text-base"><Cpu /></el-icon>
        </div>
        <div class="min-w-0">
          <div class="text-xs font-bold text-[var(--text-primary)] group-hover:text-[var(--brand)] transition-colors truncate">AI 算力</div>
          <div class="text-[10px] text-[var(--text-muted)] truncate">模型与网关配置</div>
        </div>
      </router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Cpu, Operation, PictureFilled } from '@element-plus/icons-vue'

defineProps<{
  aiReady: boolean
  aiProviderCount: number
  activeRenderCount: number
}>()

const steps = [
  { title: 'AI 剧本拆解', desc: 'Planner 分段 + Worker 并行生成分镜' },
  { title: '资产沉淀消歧', desc: '角色、场景、道具 ID 注册表自动建档' },
  { title: '连续性校验', desc: '五维视听连贯性体检与平滑修复' },
  { title: '两阶段渲染', desc: '首帧 T2I 定妆 + I2V 图生视频' }
]
</script>
