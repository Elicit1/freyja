import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import Layout from '@/layout/Index.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '工作台概览' }
      },
      {
        path: 'drama',
        name: 'Drama',
        component: () => import('@/views/drama/index.vue'),
        meta: { title: '剧作大纲与分镜' }
      },
      {
        path: 'assets',
        name: 'Assets',
        component: () => import('@/views/assets/index.vue'),
        meta: { title: '角色与场景资产' }
      },
      {
        path: 'production/video-upscale',
        name: 'VideoUpscale',
        component: () => import('@/views/production/VideoUpscale.vue'),
        meta: { title: '视频超分' }
      },
      {
        path: 'production/frame-interpolation',
        name: 'FrameInterpolation',
        component: () => import('@/views/production/FrameInterpolation.vue'),
        meta: { title: '视频补帧' }
      },
      {
        path: 'render/tasks',
        name: 'RenderTasks',
        component: () => import('@/views/task-center/index.vue'),
        meta: { title: '任务中心' }
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
        path: 'system/skills',
        name: 'AiSkills',
        component: () => import('@/views/system/skills/index.vue'),
        meta: { title: 'AI Skills 技能管理' }
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

export default router
