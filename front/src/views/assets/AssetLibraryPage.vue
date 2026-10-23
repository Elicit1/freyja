<template>
  <div class="h-[calc(100vh-96px)] flex flex-col studio-card overflow-hidden">
    <WorkspaceHeader :title="page.title" :subtitle="page.subtitle" :icon="Collection">
      <template #tag>
        <span class="studio-badge bg-[var(--brand-soft)] text-[var(--brand)]">资产中心</span>
        <span v-if="isShotReturnContext" class="studio-badge bg-amber-50 text-amber-700">保存后返回分镜并绑定</span>
      </template>
    </WorkspaceHeader>
    <div class="flex-1 overflow-y-auto p-6 bg-[var(--app-bg)]">
      <component :is="listComponent" ref="list" @asset-created="created" @asset-cancelled="returnToShot()" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Collection } from '@element-plus/icons-vue'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'

type Kind = 'character' | 'scene' | 'prop' | 'keyframe' | 'video'
const props = defineProps<{ kind: Kind; listComponent: Component }>()
const pages = {
  character: { title: '角色资产', subtitle: '管理人物身份、造型与设计图' },
  scene: { title: '场景资产', subtitle: '管理场景环境、空间设定与参考图' },
  prop: { title: '核心道具', subtitle: '管理叙事道具与设计图' },
  keyframe: { title: '关键图资产', subtitle: '管理具体帧、构图与分镜规划参考图' },
  video: { title: '视频素材', subtitle: '上传、整理与复用视频；分镜上传的参考视频自动入库' }
}
const page = computed(() => pages[props.kind])
const list = ref<{ openAsset?: (id: string) => unknown; handleCreate?: (dramaId?: string, imageUrl?: string) => unknown; openCreate?: (dramaId?: string, imageUrl?: string, shotId?: string) => unknown }>()
const route = useRoute(), router = useRouter()
function query(key: string) { const value = route.query[key]; return typeof value === 'string' ? value : Array.isArray(value) ? value[0] || undefined : undefined }
const isShotReturnContext = computed(() => query('returnTo') === 'shot' && !!query('shotId'))
onMounted(async () => {
  await nextTick()
  const assetId = query('assetId')
  if (assetId) { await list.value?.openAsset?.(assetId); return }
  if (!isShotReturnContext.value) return
  if (props.kind === 'keyframe') list.value?.openCreate?.(query('dramaId'), query('referenceImageUrl'), query('shotId'))
  else list.value?.handleCreate?.(query('dramaId'), query('referenceImageUrl'))
})
async function created(value: string | number | { id?: string | number }) {
  const id = typeof value === 'object' ? value.id : value
  if (id != null) await returnToShot(String(id))
}
async function returnToShot(assetId?: string) {
  if (!isShotReturnContext.value) return
  const params: Record<string, string> = { resumeShotId: query('shotId')! }
  if (assetId) { params.newAssetType = props.kind; params.newAssetId = assetId }
  for (const key of ['dramaId', 'episodeId', 'sceneId']) { const value = query(key); if (value) params[key] = value }
  await router.push({ name: 'Drama', query: params })
}
</script>
