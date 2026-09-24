<template>
  <div class="h-[calc(100vh-96px)] flex flex-col studio-card overflow-hidden">
    <!-- 资产中心工作区 Header -->
    <WorkspaceHeader
      title="数字资产中心"
      subtitle="集中建档与调度角色、多造型服饰、场景环境与核心道具"
      :icon="Collection"
    >
      <template #tag>
        <span class="studio-badge bg-[var(--brand-soft)] text-[var(--brand)] font-mono border border-[var(--brand-soft)]">
          数字资产沉淀库
        </span>
        <el-tooltip v-if="isShotReturnContext" content="保存后将自动返回刚才的分镜并绑定新资产" placement="bottom">
          <span class="studio-badge bg-amber-50 text-amber-700 border border-amber-200 cursor-help">
            ↩ 分镜新增模式
          </span>
        </el-tooltip>
      </template>
    </WorkspaceHeader>

    <!-- 顶部三级分类选项卡 -->
    <div class="px-6 bg-[var(--surface)] border-b border-[var(--border-default)] select-none">
      <el-tabs v-model="activeTab" class="studio-tabs">
        <el-tab-pane name="character">
          <template #label>
            <div class="flex items-center gap-2 py-1 text-xs font-semibold">
              <el-icon><UserFilled /></el-icon>
              <span>角色资产</span>
            </div>
          </template>
        </el-tab-pane>

        <el-tab-pane name="scene">
          <template #label>
            <div class="flex items-center gap-2 py-1 text-xs font-semibold">
              <el-icon><Picture /></el-icon>
              <span>场景资产</span>
            </div>
          </template>
        </el-tab-pane>

        <el-tab-pane name="prop">
          <template #label>
            <div class="flex items-center gap-2 py-1 text-xs font-semibold">
              <el-icon><Box /></el-icon>
              <span>核心道具</span>
            </div>
          </template>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 主工作区根据当前栏目呈现，避免一次性过载渲染 -->
    <div class="flex-1 overflow-y-auto p-6 custom-scrollbar bg-[var(--app-bg)]">
      <!-- 1. 角色资产 -->
      <div v-show="activeTab === 'character'">
        <CharacterList
          ref="characterListRef"
          @asset-created="handleAssetCreated('character', $event)"
          @asset-cancelled="handleAssetCancelled"
        />
      </div>

      <!-- 2. 场景资产 -->
      <div v-show="activeTab === 'scene'">
        <SceneList
          ref="sceneListRef"
          @asset-created="handleAssetCreated('scene', $event)"
          @asset-cancelled="handleAssetCancelled"
        />
      </div>

      <!-- 3. 道具资产 -->
      <div v-show="activeTab === 'prop'">
        <PropList
          ref="propListRef"
          @asset-created="handleAssetCreated('prop', $event)"
          @asset-cancelled="handleAssetCancelled"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { UserFilled, Picture, Box, Collection } from '@element-plus/icons-vue'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'
import CharacterList from './components/CharacterList.vue'
import SceneList from './components/SceneList.vue'
import PropList from './components/PropList.vue'

const activeTab = ref<'character' | 'scene' | 'prop'>('character')

const characterListRef = ref<InstanceType<typeof CharacterList>>()
const sceneListRef = ref<InstanceType<typeof SceneList>>()
const propListRef = ref<InstanceType<typeof PropList>>()

type AssetType = 'character' | 'scene' | 'prop'
const route = useRoute()
const router = useRouter()

function queryValue(value: unknown): string | undefined {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined
  return typeof value === 'string' ? value : undefined
}

const returnAssetType = computed<AssetType | undefined>(() => {
  const value = queryValue(route.query.assetType)
  return value === 'character' || value === 'scene' || value === 'prop' ? value : undefined
})

const isShotReturnContext = computed(() => {
  return queryValue(route.query.returnTo) === 'shot'
    && !!queryValue(route.query.shotId)
    && !!returnAssetType.value
})

onMounted(async () => {
  if (!isShotReturnContext.value || !returnAssetType.value) return
  activeTab.value = returnAssetType.value
  await nextTick()
  const dramaId = queryValue(route.query.dramaId)
  if (returnAssetType.value === 'character') characterListRef.value?.handleCreate(dramaId)
  if (returnAssetType.value === 'scene') sceneListRef.value?.handleCreate(dramaId)
  if (returnAssetType.value === 'prop') propListRef.value?.handleCreate(dramaId)
})

async function handleAssetCreated(assetType: AssetType, assetId: string | number) {
  if (!isShotReturnContext.value) return
  const shotId = queryValue(route.query.shotId)
  if (!shotId) return

  const query: Record<string, string> = {
    resumeShotId: shotId,
    newAssetType: assetType,
    newAssetId: String(assetId)
  }
  for (const key of ['dramaId', 'episodeId', 'sceneId']) {
    const value = queryValue(route.query[key])
    if (value) query[key] = value
  }
  await router.push({ name: 'Drama', query })
}

async function handleAssetCancelled() {
  if (!isShotReturnContext.value) return
  const shotId = queryValue(route.query.shotId)
  if (!shotId) return

  const query: Record<string, string> = {
    resumeShotId: shotId
  }
  for (const key of ['dramaId', 'episodeId', 'sceneId']) {
    const value = queryValue(route.query[key])
    if (value) query[key] = value
  }
  await router.push({ name: 'Drama', query })
}
</script>

<style scoped>
:deep(.studio-tabs .el-tabs__header) {
  margin-bottom: 0;
  border-bottom: none;
}
:deep(.studio-tabs .el-tabs__nav-wrap::after) {
  display: none;
}
:deep(.studio-tabs .el-tabs__item) {
  padding: 0 16px;
  height: 44px;
  line-height: 44px;
  color: var(--text-secondary);
  transition: all var(--transition-fast);
}
:deep(.studio-tabs .el-tabs__item.is-active) {
  color: var(--brand);
  font-weight: 600;
}
:deep(.studio-tabs .el-tabs__active-bar) {
  background-color: var(--brand);
  height: 2.5px;
  border-radius: 2px;
}
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: var(--border-default);
  border-radius: 6px;
}
</style>
