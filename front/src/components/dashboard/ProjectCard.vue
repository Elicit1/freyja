<template>
  <button type="button" class="recent-project" @click="emit('select', String(drama.id))">
    <ProjectCover class="recent-cover" :title="drama.title" :cover-url="drama.coverUrl" />
    <div class="recent-info">
      <div class="recent-title"><h3 :title="drama.title">{{ drama.title }}</h3><DictTag v-if="drama.genre" dict-type="drama_genre" :value="drama.genre" size="small" /></div>
      <p>{{ drama.episodeCount || 0 }} 集 <span>·</span> {{ drama.shotCount || 0 }} 个分镜</p>
      <small>更新于 {{ projectDate(drama.updateTime) }}</small>
    </div>
    <span class="recent-enter">继续创作 <el-icon><ArrowRight /></el-icon></span>
  </button>
</template>

<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'
import DictTag from '@/components/DictTag.vue'
import ProjectCover from '@/components/media/ProjectCover.vue'
import { projectDate } from '@/utils/project-display'
import type { RecentDramaItem } from '@/types/dashboard'
defineProps<{ drama: RecentDramaItem }>()
const emit = defineEmits<{ select: [id: string] }>()
</script>

<style scoped>
.recent-project { display: flex; align-items: center; gap: 16px; padding: 12px; width: 100%; min-width: 0; text-align: left; background: var(--surface); border: 1px solid var(--border-default); border-radius: 12px; transition: border-color .15s, box-shadow .15s; }
.recent-project:hover { border-color: var(--brand); box-shadow: var(--shadow-card); }
.recent-project:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
.recent-cover { width: 120px; height: 72px; border-radius: 8px; flex-shrink: 0; }
.recent-cover :deep(.cover-title), .recent-cover :deep(.cover-caption) { display: none; }
.recent-info { flex: 1; min-width: 0; }
.recent-title { display: flex; align-items: center; gap: 8px; min-width: 0; }
h3 { font-size: 14px; font-weight: 650; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
p { font-size: 12px; color: var(--text-secondary); margin: 8px 0 5px; }
p span { margin: 0 6px; color: var(--text-muted); }
small { font-size: 11px; color: var(--text-muted); }
.recent-enter { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--brand); flex-shrink: 0; }
@media (max-width: 1250px) { .recent-cover { width: 88px; height: 72px; } .recent-enter { font-size: 0; gap: 0; } .recent-enter .el-icon { font-size: 16px; } }
</style>
