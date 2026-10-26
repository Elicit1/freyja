<template>
  <ProductionWorkspace title="工作台概览" :subtitle="greetingText + '，从最近的项目继续创作。'" :icon="Odometer" body-class="overview-body">
    <template #actions>
      <el-button :loading="loading" :icon="Refresh" @click="fetchStats">刷新统计</el-button>
      <el-button type="primary" :icon="Film" @click="router.push('/drama')">短剧项目库</el-button>
    </template>
    <div class="overview-metrics" :aria-busy="loading">
      <MetricCard title="短剧项目" :value="stats.dramaCount" unit="部" :icon="Film" :sub-text="`${stats.episodeCount} 集 · ${stats.sceneCount} 场次大纲`" />
      <MetricCard title="分镜镜头" :value="stats.shotCount" unit="镜" :icon="VideoCamera" :sub-text="`成片 ${stats.renderedVideoCount} · 首帧 ${stats.renderedImageCount}`" />
      <MetricCard title="数字资产" :value="stats.totalAssetCount" unit="项" :icon="PictureFilled" :sub-text="`角色 ${stats.characterCount} · 场景 ${stats.sceneAssetCount} · 道具 ${stats.propCount}`" />
      <MetricCard title="AI 提供商" :value="stats.aiProviderCount" unit="个" :icon="Cpu" :sub-text="`累计 ${stats.aiTaskCount} 次 AI 任务`" />
    </div>
    <div class="overview-content">
      <section class="recent-section">
        <div class="overview-section-heading"><h2><el-icon><Film /></el-icon> 最近编辑项目</h2><router-link to="/drama">全部项目 <el-icon><ArrowRight /></el-icon></router-link></div>
        <el-skeleton v-if="loading && !stats.recentDramas.length" :rows="8" animated class="studio-card p-5" />
        <div v-else-if="stats.recentDramas.length" class="recent-projects">
          <ProjectCard v-for="drama in stats.recentDramas" :key="String(drama.id)" :drama="drama" @select="handleGoDrama" />
        </div>
        <div v-else class="studio-card overview-empty"><el-icon><Film /></el-icon><h3>你的第一个故事，从这里开始</h3><p>创建短剧项目，整理剧本与角色，再开始分镜制作。</p><el-button type="primary" @click="router.push('/drama')">创建短剧项目</el-button></div>
      </section>
      <section><div class="overview-section-heading"><h2><el-icon><VideoCamera /></el-icon> 制作概况</h2></div>
        <ProductionStatus :ai-ready="stats.aiServiceReady" :ai-provider-count="stats.aiProviderCount" :shot-count="stats.shotCount" :rendered-video-count="stats.renderedVideoCount" :rendered-image-count="stats.renderedImageCount" :tasks="productionTasks" />
      </section>
    </div>
    <section class="overview-shortcuts">
      <router-link v-for="item in shortcuts" :key="item.to" :to="item.to" class="studio-card overview-shortcut"><span class="shortcut-icon"><component :is="item.icon" /></span><span><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span><el-icon><ArrowRight /></el-icon></router-link>
    </section>
  </ProductionWorkspace>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, Film, VideoCamera, PictureFilled, Cpu, ArrowRight, Microphone, MagicStick, Odometer } from '@element-plus/icons-vue'
import ProductionWorkspace from '@/components/workspace/ProductionWorkspace.vue'
import MetricCard from '@/components/dashboard/MetricCard.vue'
import ProjectCard from '@/components/dashboard/ProjectCard.vue'
import ProductionStatus from '@/components/dashboard/ProductionStatus.vue'
import { useTaskCenterStore } from '@/store/taskCenter'
import { getDashboardStats } from '@/api/dashboard'
import type { DashboardStats } from '@/types/dashboard'

const router = useRouter()
const taskStore = useTaskCenterStore()
const productionTasks = computed(() => taskStore.activeTasks.filter(task => task.category === 'RENDER' || task.category === 'MEDIA_PROCESS'))
const loading = ref(false)
const shortcuts = [
  { title: '角色资产', description: '管理人物、造型与视觉参考', icon: PictureFilled, to: '/assets/characters' },
  { title: '角色配音', description: '设计声线、克隆配音与试听', icon: Microphone, to: '/dubbing/characters' },
  { title: '视频后期', description: '超分增强与平滑补帧', icon: MagicStick, to: '/production/video-upscale' }
]

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

<style scoped>
.overview-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin-bottom: 18px; }
.overview-content { display: grid; grid-template-columns: minmax(0, 1.8fr) minmax(300px, 1fr); gap: 20px; }
.overview-content > * { min-width: 0; }
.overview-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 0 14px; }
h2 { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 650; }
h2 .el-icon { color: var(--brand); }
.overview-section-heading a { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--brand); }
.recent-projects { display: flex; flex-direction: column; gap: 12px; }
.overview-empty { padding: 50px 20px; text-align: center; }
.overview-empty > .el-icon { font-size: 36px; color: var(--brand); margin-bottom: 16px; }
.overview-empty h3 { font-size: 16px; font-weight: 600; }
.overview-empty p { font-size: 12px; color: var(--text-muted); margin: 12px 0 20px; }
.overview-shortcuts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-top: 16px; }
.overview-shortcut { display: flex; align-items: center; gap: 12px; padding: 14px; }
.shortcut-icon { display: flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 10px; background: var(--brand-soft); color: var(--brand); flex-shrink: 0; }
.shortcut-icon > svg { width: 22px; height: 22px; }
.overview-shortcut > span:nth-child(2) { min-width: 0; }
.overview-shortcut strong { display: block; font-size: 14px; color: var(--text-primary); }
.overview-shortcut small { display: block; font-size: 11px; color: var(--text-muted); margin-top: 5px; }
.overview-shortcut > .el-icon { margin-left: auto; color: var(--brand); }
@media (max-width: 1200px) { .overview-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .overview-content { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 800px) { .overview-shortcuts { grid-template-columns: minmax(0, 1fr); } }
</style>
