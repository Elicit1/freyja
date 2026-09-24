<template>
  <div class="h-full flex flex-col bg-[var(--surface)] border-r border-[var(--border-default)] select-none">
    <!-- 头部工具栏 -->
    <div class="p-3 border-b border-[var(--border-default)] flex items-center justify-between bg-[var(--surface-muted)]">
      <div class="flex items-center gap-2 font-bold text-[var(--text-primary)] text-xs tracking-wide">
        <el-icon class="text-[var(--brand)] text-sm"><Film /></el-icon>
        <span>剧作大纲</span>
        <span class="studio-badge bg-[var(--surface)] text-[var(--text-secondary)] border border-[var(--border-default)] !py-0 !px-1.5 font-mono !text-[10px]">
          {{ treeData?.episodes?.length || 0 }} 集
        </span>
      </div>
      <el-button type="primary" link size="small" class="!text-xs" @click="$emit('add-episode')">
        <el-icon class="mr-0.5"><Plus /></el-icon> 新建集
      </el-button>
    </div>

    <!-- 树形大纲列表 -->
    <div class="flex-1 overflow-y-auto p-2 space-y-1.5 custom-scrollbar">
      <div v-if="!treeData?.episodes?.length" class="text-center py-12 text-[var(--text-muted)] text-xs">
        <el-icon class="text-3xl text-[var(--text-muted)] mb-2 block mx-auto opacity-50"><FolderOpened /></el-icon>
        <p class="mb-3">暂无剧集与场次</p>
        <el-button type="primary" size="small" plain @click="$emit('add-episode')">
          创建第 1 集
        </el-button>
      </div>

      <div
        v-for="ep in treeData?.episodes || []"
        :key="String(ep.id)"
        class="border border-[var(--border-default)] rounded-xl overflow-hidden bg-[var(--surface)] transition-all shadow-xs"
        :class="isEpisodeActive(ep.id) ? 'border-[var(--brand)] ring-1 ring-[var(--brand-soft)]' : 'hover:border-[var(--border-strong)]'"
      >
        <!-- 剧集标题行 -->
        <div
          class="flex items-center justify-between px-2.5 py-2 cursor-pointer transition-colors group relative"
          :class="isEpisodeActive(ep.id) ? 'bg-[var(--brand-soft)] text-[var(--brand)] font-semibold' : 'hover:bg-[var(--surface-hover)] text-[var(--text-primary)]'"
          @click="handleSelectEpisode(ep.id)"
        >
          <!-- 左侧激活竖条 -->
          <span
            v-if="isEpisodeActive(ep.id)"
            class="absolute left-0 top-2 bottom-2 w-[3px] bg-[var(--brand)] rounded-r"
          ></span>

          <div class="flex items-center gap-1.5 truncate flex-1 min-w-0">
            <el-icon
              class="text-xs text-[var(--text-muted)] transition-transform duration-200 cursor-pointer"
              :class="{ 'rotate-90': isExpanded(ep.id) }"
              @click.stop="toggleExpand(ep.id)"
            >
              <ArrowRight />
            </el-icon>
            <span class="text-[11px] font-mono px-1.5 py-0.5 bg-[var(--surface-muted)] text-[var(--text-secondary)] rounded font-semibold border border-[var(--border-default)]">
              E{{ String(ep.episodeNo).padStart(2, '0') }}
            </span>
            <span class="text-xs truncate max-w-[125px]" :title="ep.title">{{ ep.title }}</span>
          </div>

          <div class="flex items-center gap-1.5 shrink-0">
            <!-- 分镜统计胶囊 -->
            <span class="text-[11px] font-mono text-[var(--text-muted)] group-hover:hidden">
              <span :class="{ 'text-emerald-600 font-bold': ep.renderedShotCount === ep.shotCount && (ep.shotCount || 0) > 0 }">
                {{ ep.renderedShotCount || 0 }}/{{ ep.shotCount || 0 }}
              </span>
            </span>

            <!-- 操作菜单 -->
            <div class="hidden group-hover:flex items-center gap-1" @click.stop>
              <el-tooltip content="为此集新增场次" placement="top">
                <el-button type="primary" link size="small" class="!p-0.5" @click="$emit('add-scene', ep.id)">
                  <el-icon :size="13"><Plus /></el-icon>
                </el-button>
              </el-tooltip>
              <el-dropdown trigger="click" size="small" @command="(cmd: string) => handleEpCommand(cmd, ep.id)">
                <el-button link size="small" class="!p-0.5 text-[var(--text-muted)] hover:text-[var(--text-primary)]">
                  <el-icon :size="13"><MoreFilled /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">编辑剧集</el-dropdown-item>
                    <el-dropdown-item command="add-scene">新建场次</el-dropdown-item>
                    <el-dropdown-item command="delete" divided class="!text-[var(--danger)]">删除剧集</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
        </div>

        <!-- 场次子列表 (展开状态) -->
        <div v-show="isExpanded(ep.id)" class="bg-[var(--surface-muted)]/50 border-t border-[var(--border-default)] py-1 pl-4 pr-1.5 space-y-1">
          <div v-if="!ep.scenes?.length" class="py-2 text-[11px] text-[var(--text-muted)] text-center flex items-center justify-center gap-1">
            <span>无场次</span>
            <el-button type="primary" link size="small" class="!text-[11px]" @click="$emit('add-scene', ep.id)">
              + 加场
            </el-button>
          </div>

          <div
            v-for="sc in ep.scenes || []"
            :key="String(sc.id)"
            class="flex items-center justify-between px-2 py-1.5 rounded-lg cursor-pointer text-xs group transition-colors relative"
            :class="isSceneActive(sc.id) ? 'bg-[var(--brand)] text-white font-medium shadow-xs' : 'hover:bg-[var(--surface-hover)] text-[var(--text-primary)]'"
            @click="handleSelectScene(sc.id, ep.id)"
          >
            <div class="flex items-center gap-1.5 truncate flex-1 min-w-0">
              <el-icon class="text-[11px] opacity-70 shrink-0"><VideoCamera /></el-icon>
              <span class="truncate max-w-[125px]" :title="sc.name">{{ sc.name }}</span>
            </div>

            <div class="flex items-center gap-1.5 shrink-0">
              <span
                class="text-[10px] px-1.5 py-0.2 rounded font-mono"
                :class="isSceneActive(sc.id) ? 'bg-white/20 text-white' : 'bg-[var(--surface)] text-[var(--text-secondary)] border border-[var(--border-default)]'"
              >
                {{ sc.shotGroups?.length ? `${sc.shotGroups.length}组·` : '' }}{{ sc.shots?.length || 0 }}镜
              </span>

              <div class="hidden group-hover:flex items-center" @click.stop>
                <el-dropdown trigger="click" size="small" @command="(cmd: string) => handleScCommand(cmd, sc.id)">
                  <el-button link size="small" class="!p-0.5" :class="isSceneActive(sc.id) ? 'text-white' : 'text-[var(--text-muted)]'">
                    <el-icon :size="12"><MoreFilled /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="edit">编辑场次</el-dropdown-item>
                      <el-dropdown-item command="delete" divided class="!text-[var(--danger)]">删除场次</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { Plus, MoreFilled, Film, VideoCamera, ArrowRight, FolderOpened } from '@element-plus/icons-vue'
import type { DramaTree } from '@/types/drama'

const props = defineProps<{
  treeData: DramaTree | null
  selectedEpisodeId: number | string | null
  selectedSceneId: number | string | null
}>()

const emit = defineEmits<{
  (e: 'select-episode', episodeId: any): void
  (e: 'select-scene', sceneId: any, episodeId: any): void
  (e: 'add-episode'): void
  (e: 'edit-episode', episodeId: any): void
  (e: 'delete-episode', episodeId: any): void
  (e: 'add-scene', episodeId: any): void
  (e: 'edit-scene', sceneId: any): void
  (e: 'delete-scene', sceneId: any): void
}>()

const expandedEpisodeIds = ref<Set<string>>(new Set())

function isEpisodeActive(epId: any): boolean {
  return String(props.selectedEpisodeId) === String(epId) && !props.selectedSceneId
}

function isSceneActive(scId: any): boolean {
  return String(props.selectedSceneId) === String(scId)
}

watch(
  () => props.treeData,
  (tree) => {
    if (tree?.episodes?.length && expandedEpisodeIds.value.size === 0) {
      tree.episodes.slice(0, 3).forEach(ep => expandedEpisodeIds.value.add(String(ep.id)))
    }
  },
  { immediate: true }
)

watch(
  [() => props.selectedEpisodeId, () => props.selectedSceneId, () => props.treeData],
  ([epId, scId, tree]) => {
    if (epId) {
      expandedEpisodeIds.value.add(String(epId))
    }
    if (scId && tree?.episodes) {
      for (const ep of tree.episodes) {
        if (ep.scenes?.some(s => String(s.id) === String(scId))) {
          expandedEpisodeIds.value.add(String(ep.id))
          break
        }
      }
    }
  },
  { immediate: true }
)

function isExpanded(epId: any) {
  return expandedEpisodeIds.value.has(String(epId))
}

function toggleExpand(epId: any) {
  const key = String(epId)
  if (expandedEpisodeIds.value.has(key)) {
    expandedEpisodeIds.value.delete(key)
  } else {
    expandedEpisodeIds.value.add(key)
  }
}

function handleSelectEpisode(epId: any) {
  expandedEpisodeIds.value.add(String(epId))
  emit('select-episode', epId)
}

function handleSelectScene(sceneId: any, epId: any) {
  emit('select-scene', sceneId, epId)
}

function handleEpCommand(cmd: string, epId: any) {
  if (cmd === 'edit') emit('edit-episode', epId)
  else if (cmd === 'add-scene') emit('add-scene', epId)
  else if (cmd === 'delete') emit('delete-episode', epId)
}

function handleScCommand(cmd: string, scId: any) {
  if (cmd === 'edit') emit('edit-scene', scId)
  else if (cmd === 'delete') emit('delete-scene', scId)
}
</script>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 4px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: var(--border-default);
  border-radius: 4px;
}
</style>
