<template>
  <div class="space-y-6">
    <!-- 1. 欢迎与主操作控制台 Header -->
    <div class="studio-card p-6 flex flex-col md:flex-row md:items-center md:justify-between gap-5">
      <div class="space-y-1">
        <div class="flex items-center gap-2.5">
          <h1 class="text-xl font-bold text-[var(--text-primary)] tracking-tight">
            {{ greetingText }}，欢迎回到 Freyja 数字片场
          </h1>
          <span class="studio-badge bg-[var(--brand-soft)] text-[var(--brand)] border border-[var(--brand-soft)]">
            AI 工业化制片
          </span>
        </div>
        <p class="text-xs text-[var(--text-secondary)]">
          继续推进你的短剧剧情策划、角色定妆与并行分镜制作任务
        </p>
      </div>

      <div class="flex items-center gap-3 shrink-0">
        <el-button
          :loading="loading"
          class="!border-[var(--border-default)] !text-[var(--text-secondary)] hover:!text-[var(--text-primary)] hover:!border-[var(--border-strong)]"
          @click="fetchStats"
        >
          <el-icon class="mr-1"><Refresh /></el-icon> 刷新统计
        </el-button>

        <router-link to="/drama">
          <el-button type="primary" class="!shadow-xs">
            <el-icon class="mr-1"><Film /></el-icon> 进入分镜工作台
          </el-button>
        </router-link>
      </div>
    </div>

    <!-- 2. 核心四大指标 (MetricCards) -->
    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4" v-loading="loading">
      <!-- 1. 短剧项目 -->
      <MetricCard
        title="短剧项目总数"
        :value="stats.dramaCount || 0"
        unit="部"
        :icon="Film"
        icon-bg-class="bg-blue-50 text-blue-600"
        :sub-text="`共 ${stats.episodeCount || 0} 集 · ${stats.sceneCount || 0} 场次大纲`"
      />

      <!-- 2. 分镜镜头 -->
      <MetricCard
        title="分镜镜头总数"
        :value="stats.shotCount || 0"
        unit="镜"
        :icon="VideoCamera"
        icon-bg-class="bg-emerald-50 text-emerald-600"
      >
        <template #extra>
          <div class="flex items-center gap-1.5 text-[11px] text-emerald-600">
            <span>成片视频 {{ stats.renderedVideoCount || 0 }}</span>
            <span class="text-[var(--border-strong)]">·</span>
            <span>定妆首帧 {{ stats.renderedImageCount || 0 }}</span>
          </div>
        </template>
      </MetricCard>

      <!-- 3. 数字资产 -->
      <MetricCard
        title="数字资产沉淀"
        :value="stats.totalAssetCount || 0"
        unit="项"
        :icon="PictureFilled"
        icon-bg-class="bg-amber-50 text-amber-600"
        :sub-text="`角色 ${stats.characterCount || 0} · 场景 ${stats.sceneAssetCount || 0} · 道具 ${stats.propCount || 0}`"
      />

      <!-- 4. AI 算力与模型 -->
      <MetricCard
        title="AI 算力与模型"
        :value="stats.aiProviderCount || 0"
        unit="个提供商"
        :icon="Cpu"
        icon-bg-class="bg-purple-50 text-purple-600"
        :sub-text="`累计调度 ${stats.aiTaskCount || 0} 次 AI 智能任务`"
      />
    </div>

    <!-- 3. 主工作区布局：最近活跃短剧 (8 cols) + 制作状态集成 (4 cols) -->
    <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">
      <!-- Left: 最近活跃短剧 (8 cols) -->
      <div class="lg:col-span-8 space-y-4">
        <div class="studio-card p-5">
          <div class="flex items-center justify-between pb-4 border-b border-[var(--border-default)] mb-4">
            <div class="flex items-center gap-2">
              <el-icon class="text-base text-[var(--brand)]"><Film /></el-icon>
              <h3 class="font-bold text-sm text-[var(--text-primary)]">最近活跃短剧项目</h3>
            </div>
            <router-link
              to="/drama"
              class="text-xs text-[var(--brand)] hover:underline font-medium flex items-center gap-1"
            >
              全部短剧 <el-icon><ArrowRight /></el-icon>
            </router-link>
          </div>

          <!-- 短剧列表卡片网格 -->
          <div
            v-if="stats.recentDramas && stats.recentDramas.length > 0"
            class="grid grid-cols-1 sm:grid-cols-2 gap-3.5"
          >
            <ProjectCard
              v-for="drama in stats.recentDramas"
              :key="String(drama.id)"
              :drama="drama"
              @select="handleGoDrama"
            />
          </div>

          <!-- 空状态 -->
          <div v-else class="py-14 text-center text-[var(--text-muted)] space-y-3">
            <el-icon class="text-4xl text-[var(--text-muted)] block mx-auto"><Film /></el-icon>
            <p class="text-xs font-medium">暂无活跃短剧记录</p>
            <router-link to="/drama">
              <el-button type="primary" size="small">
                + 新建短剧或 AI 剧本拆解
              </el-button>
            </router-link>
          </div>
        </div>
      </div>

      <!-- Right: 片场制作状态看板 (4 cols) -->
      <div class="lg:col-span-4">
        <ProductionStatus
          :ai-ready="stats.aiServiceReady"
          :ai-provider-count="stats.aiProviderCount"
          :active-render-count="renderTaskStore.activeCount"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  Refresh,
  Film,
  VideoCamera,
  PictureFilled,
  Cpu,
  ArrowRight
} from '@element-plus/icons-vue'
import MetricCard from '@/components/dashboard/MetricCard.vue'
import ProjectCard from '@/components/dashboard/ProjectCard.vue'
import ProductionStatus from '@/components/dashboard/ProductionStatus.vue'
import { useRenderTaskStore } from '@/store/renderTask'
import { getDashboardStats } from '@/api/dashboard'
import type { DashboardStats } from '@/types/dashboard'

const router = useRouter()
const renderTaskStore = useRenderTaskStore()
const loading = ref(false)

const stats = ref<DashboardStats>({
  dramaCount: 0,
  episodeCount: 0,
  sceneCount: 0,
  shotCount: 0,
  characterCount: 0,
  sceneAssetCount: 0,
  propCount: 0,
  totalAssetCount: 0,
  renderedVideoCount: 0,
  renderedImageCount: 0,
  aiProviderCount: 0,
  aiTaskCount: 0,
  aiServiceReady: false,
  storageReady: true,
  recentDramas: []
})

const greetingText = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

async function fetchStats() {
  loading.value = true
  try {
    const res = await getDashboardStats()
    if (res) {
      stats.value = res
    }
  } catch (error) {
    console.error('获取首页统计失败:', error)
  } finally {
    loading.value = false
  }
}

/**
 * 铁律遵循：保持 19 位雪花 ID 为纯字符串，严禁调用 Number() 转换
 */
function handleGoDrama(dramaId: string) {
  router.push({ name: 'Drama', query: { dramaId } })
}

onMounted(() => {
  fetchStats()
})
</script>
