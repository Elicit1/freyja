import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import Layout from '@/layout/Index.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dubbing/characters', name: 'DubbingCharacters',
        component: () => import('@/views/dubbing/Characters.vue'), meta: { title: '角色配音', productionWorkspace: true }
      },
      {
        path: 'dubbing/effects', name: 'DubbingEffects',
        component: () => import('@/views/dubbing/Effects.vue'), meta: { title: '声音特效配置', productionWorkspace: true }
      },
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '工作台概览', productionWorkspace: true }
      },
      {
        path: 'drama',
        name: 'Drama',
        component: () => import('@/views/drama/index.vue'),
        meta: { title: '剧作大纲与分镜', productionWorkspace: true }
      },
      {
        path: 'assets',
        name: 'Assets',
        redirect: to => {
          const paths: Record<string, string> = { character: 'characters', scene: 'scenes', prop: 'props', keyframe: 'keyframes', video: 'videos' }
          return { path: `/assets/${paths[String(to.query.assetType || '')] || 'characters'}`, query: to.query }
        }
      },
      { path: 'assets/characters', name: 'AssetCharacters', component: () => import('@/views/assets/Characters.vue'), meta: { title: '角色资产', productionWorkspace: true } },
      { path: 'assets/scenes', name: 'AssetScenes', component: () => import('@/views/assets/Scenes.vue'), meta: { title: '场景资产', productionWorkspace: true } },
      { path: 'assets/props', name: 'AssetProps', component: () => import('@/views/assets/Props.vue'), meta: { title: '核心道具', productionWorkspace: true } },
      { path: 'assets/keyframes', name: 'AssetKeyframes', component: () => import('@/views/assets/Keyframes.vue'), meta: { title: '关键图资产', productionWorkspace: true } },
      { path: 'assets/videos', name: 'AssetVideos', component: () => import('@/views/assets/Videos.vue'), meta: { title: '视频素材', productionWorkspace: true } },
      { path: 'assets/audios', name: 'AssetAudios', component: () => import('@/views/assets/Audios.vue'), meta: { title: '音频素材', productionWorkspace: true } },
      {
        path: 'production/video-upscale',
        name: 'VideoUpscale',
        component: () => import('@/views/production/VideoUpscale.vue'),
        meta: { title: '视频超分', productionWorkspace: true }
      },
      {
        path: 'production/frame-interpolation',
        name: 'FrameInterpolation',
        component: () => import('@/views/production/FrameInterpolation.vue'),
        meta: { title: '视频补帧', productionWorkspace: true }
      },
      {
        path: 'render/tasks',
        name: 'RenderTasks',
        component: () => import('@/views/task-center/index.vue'),
        meta: { title: '任务中心', productionWorkspace: true }
      },
      {
        path: 'system/dict',
        name: 'DictManage',
        component: () => import('@/views/system/dict/index.vue'),
        meta: { title: '通用数据字典' }
      },
      {
        path: 'system/ai-provider',
        name: 'AiProvider',
        component: () => import('@/views/system/ai-provider/index.vue'),
        meta: { title: 'AI 提供商配置' }
      },
      {
        path: 'system/config',
        name: 'SysConfig',
        component: () => import('@/views/system/config/index.vue'),
        meta: { title: '系统参数配置' }
      },
      {
        path: 'system/ai-capabilities',
        name: 'AiCapabilities',
        component: () => import('@/views/system/capabilities/index.vue'),
        meta: { title: 'AI 能力管理' }
      },
      {
        path: 'system/mcp',
        name: 'McpSettings',
        component: () => import('@/views/system/mcp/index.vue'),
        meta: { title: 'MCP 服务' }
      },
      {
        path: 'system/skills',
        name: 'AiSkills',
        component: () => import('@/views/system/skills/index.vue'),
        meta: { title: 'AI Skills 技能管理' }
      },
      {
        path: 'system/agent',
        name: 'AgentConfig',
        component: () => import('@/views/system/agent/index.vue'),
        meta: { title: 'Agent 配置' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach((to, _from, next) => {
  if (to.meta?.title) {
    document.title = `${to.meta.title} - Freyja`
  }
  next()
})

router.onError((error, to) => {
  const errMsg = (error?.message || '').toLowerCase()
  const isChunkLoadFailed =
    errMsg.includes('failed to fetch dynamically imported module') ||
    errMsg.includes('loading chunk') ||
    errMsg.includes('loading css chunk')

  if (isChunkLoadFailed) {
    const targetUrl = to?.fullPath || window.location.pathname + window.location.search
    const reloadKey = 'freyja_chunk_reload_' + targetUrl
    const lastReload = sessionStorage.getItem(reloadKey)
    const now = Date.now()
    if (!lastReload || now - Number(lastReload) > 10000) {
      sessionStorage.setItem(reloadKey, String(now))
      window.location.href = targetUrl
    }
  }
})

export default router
