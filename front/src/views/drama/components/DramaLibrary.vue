<template>
  <ProductionWorkspace title="短剧项目库" subtitle="管理故事设定，继续你的分镜创作" :icon="Film">
    <template #tag><span class="studio-badge bg-[var(--brand-soft)] text-[var(--brand)]">{{ total }} 部项目</span></template>
    <template #actions>
      <el-button @click="emit('export')" :icon="Download">视频打包下载</el-button>
      <el-button plain type="primary" @click="emit('decompose')" :icon="MagicStick">AI 剧本拆解</el-button>
      <el-button type="primary" @click="emit('create')" :icon="Plus">新建短剧</el-button>
    </template>
    <template #toolbar>
      <form class="library-filters" @submit.prevent="emit('search')">
        <el-input :model-value="query.title" @update:model-value="updateQuery({ title: $event })" placeholder="搜索短剧名称" aria-label="搜索短剧名称" clearable class="library-search"><template #prefix><el-icon><Search /></el-icon></template></el-input>
        <GenreInput :model-value="query.genre" @update:model-value="updateQuery({ genre: $event })" placeholder="全部题材" :show-suggestions="false" class="library-genre" />
        <DictSelect :model-value="query.status" @update:model-value="updateQuery({ status: String($event || '') })" dict-type="drama_status" placeholder="全部状态" clearable class="library-status" />
        <el-button type="primary" native-type="submit">查询</el-button><el-button @click="emit('reset')">重置</el-button>
        <el-radio-group v-model="displayMode" size="small" class="library-view" aria-label="项目显示方式"><el-radio-button value="grid"><el-icon><Grid /></el-icon> 网格</el-radio-button><el-radio-button value="list"><el-icon><Tickets /></el-icon> 列表</el-radio-button></el-radio-group>
      </form>
    </template>
    <div v-loading="loading" class="library-content">
      <el-skeleton v-if="loading && !items.length" :rows="8" animated />
      <div v-else-if="!items.length" class="library-empty"><span class="empty-icon"><el-icon><Film /></el-icon></span><h2>{{ hasFilters ? '没有找到匹配的项目' : '为下一个故事，建立一个片场' }}</h2><p>{{ hasFilters ? '试试其他名称、题材或项目状态。' : '从故事设定开始，将剧本、角色与分镜汇聚在这里。' }}</p><el-button type="primary" @click="hasFilters ? emit('reset') : emit('create')">{{ hasFilters ? '清空筛选' : '新建短剧项目' }}</el-button></div>
      <div v-else class="library-projects" :class="{ 'is-list': displayMode === 'list' }">
        <article v-for="drama in items" :key="String(drama.id)" class="studio-card library-project">
          <button class="project-cover-button" type="button" :aria-label="'进入' + drama.title + '工作台'" @click="emit('enter', drama)">
            <ProjectCover class="library-cover" :title="drama.title" :cover-url="drama.coverUrl">
              <div class="cover-tags"><DictTag v-if="drama.genre" dict-type="drama_genre" :value="drama.genre" size="small" /><span class="aspect-tag">{{ drama.aspectRatio || '未设画幅' }}</span></div>
              <span v-if="drama.stylePreset" class="cover-style">{{ drama.stylePreset }}</span>
            </ProjectCover>
          </button>
          <div class="project-details">
            <div class="project-title"><button type="button" @click="emit('enter', drama)" :title="drama.title">{{ drama.title }}</button><DictTag dict-type="drama_status" :value="drama.status" size="small" /></div>
            <p class="project-synopsis">{{ drama.synopsis || '尚未填写故事梗概，进入项目完善你的创作设定。' }}</p>
            <div class="project-progress"><div><span>{{ drama.renderedShotCount || 0 }} / {{ drama.shotCount || 0 }} 镜已渲染</span><strong>{{ completionPercent(drama.renderedShotCount, drama.shotCount) }}%</strong></div><el-progress :percentage="completionPercent(drama.renderedShotCount, drama.shotCount)" :stroke-width="5" :show-text="false" color="var(--brand)" /></div>
            <div class="project-meta"><span>{{ drama.episodeCount || 0 }} / {{ drama.targetEpisodes || 0 }} 集</span><span>更新于 {{ projectDate(drama.updateTime) }}</span></div>
            <div class="project-actions">
              <el-dropdown trigger="click" @command="(command: string) => handleAction(command, drama)"><el-button :icon="MoreFilled" :aria-label="drama.title + '的更多操作'" /><template #dropdown><el-dropdown-menu><el-dropdown-item command="edit" :icon="Edit">编辑项目</el-dropdown-item><el-dropdown-item command="decompose" :icon="MagicStick">AI 剧本拆解</el-dropdown-item><el-dropdown-item command="delete" :icon="Delete" divided class="!text-[var(--danger)]">删除项目</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
              <el-button type="primary" @click="emit('enter', drama)">进入工作台 <el-icon class="ml-1"><ArrowRight /></el-icon></el-button>
            </div>
          </div>
        </article>
      </div>
    </div>
    <template #footer>
      <span class="library-total">共 {{ total }} 部短剧</span>
      <el-pagination :current-page="query.current || 1" :page-size="query.size || 12" :total="total" :page-sizes="[12, 24, 48]" layout="sizes, prev, pager, next" :pager-count="5" @current-change="changePage" @size-change="changeSize" />
    </template>
  </ProductionWorkspace>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { Film, Download, MagicStick, Plus, Search, Grid, Tickets, MoreFilled, Edit, Delete, ArrowRight } from '@element-plus/icons-vue'
import ProductionWorkspace from '@/components/workspace/ProductionWorkspace.vue'
import ProjectCover from '@/components/media/ProjectCover.vue'
import DictTag from '@/components/DictTag.vue'
import DictSelect from '@/components/DictSelect.vue'
import GenreInput from '@/components/GenreInput.vue'
import { completionPercent, projectDate } from '@/utils/project-display'
import type { Drama, DramaQuery } from '@/types/drama'
const props = defineProps<{ items: Drama[]; total: number; loading: boolean; query: DramaQuery }>()
const emit = defineEmits<{ 'update:query': [value: DramaQuery]; search: []; reset: []; load: []; create: []; export: []; decompose: [drama?: Drama]; enter: [drama: Drama]; edit: [id: Drama['id']]; delete: [drama: Drama] }>()
const displayMode = ref('grid')
const hasFilters = computed(() => !!(props.query.title || props.query.genre || props.query.status))
function updateQuery(patch: Partial<DramaQuery>) { emit('update:query', { ...props.query, ...patch }) }
function changePage(current: number) { updateQuery({ current }); emit('load') }
function changeSize(size: number) { updateQuery({ size, current: 1 }); emit('load') }
function handleAction(command: string, drama: Drama) {
  if (command === 'edit') emit('edit', drama.id)
  else if (command === 'decompose') emit('decompose', drama)
  else if (command === 'delete') emit('delete', drama)
}
</script>

<style scoped>
.library-filters { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.library-search { width: 240px; } .library-genre { width: 160px; } .library-status { width: 140px; }
.library-view { margin-left: auto; }
.library-projects { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(290px, 100%), 1fr)); gap: 18px; }
.library-project { overflow: hidden; min-width: 0; }
.project-cover-button { display: block; width: 100%; text-align: inherit; }
.project-cover-button:focus-visible, .project-title button:focus-visible { outline: 2px solid var(--brand); outline-offset: -3px; }
.library-cover { width: 100%; aspect-ratio: 16 / 9; max-height: 200px; }
.cover-tags { position: absolute; top: 12px; left: 12px; right: 12px; display: flex; align-items: center; gap: 6px; }
.aspect-tag, .cover-style { background: rgba(11,15,23,.7); color: #fff; padding: 3px 7px; border-radius: 5px; font-size: 10px; backdrop-filter: blur(8px); }
.cover-style { position: absolute; bottom: 10px; right: 10px; max-width: 80%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-details { padding: 16px; }
.project-title { display: flex; align-items: center; gap: 8px; }
.project-title button { font-size: 16px; font-weight: 650; text-align: left; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; min-width: 0; }
.project-title button:hover { color: var(--brand); }
.project-synopsis { margin: 10px 0 16px; color: var(--text-secondary); font-size: 12px; line-height: 1.8; height: 43px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
.project-progress > div:first-child { display: flex; justify-content: space-between; gap: 8px; font-size: 11px; color: var(--text-secondary); margin-bottom: 8px; }
.project-progress strong { color: var(--brand); font-variant-numeric: tabular-nums; }
.project-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 12px 0; font-size: 10px; color: var(--text-muted); }
.project-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 12px; border-top: 1px solid var(--border-default); }
.library-total { margin-right: auto; align-self: center; font-size: 12px; color: var(--text-muted); }
.library-empty { min-height: 340px; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; padding: 20px; }
.empty-icon { display: flex; align-items: center; justify-content: center; width: 64px; height: 64px; border-radius: 18px; font-size: 30px; background: var(--brand-soft); color: var(--brand); margin-bottom: 20px; }
.library-empty h2 { font-size: 18px; font-weight: 600; } .library-empty p { font-size: 12px; color: var(--text-muted); margin: 10px 0 22px; }
.is-list { grid-template-columns: minmax(0, 1fr); }
.is-list .library-project { display: grid; grid-template-columns: 220px minmax(0, 1fr); }
.is-list .project-cover-button, .is-list .library-cover { height: 100%; max-height: none; }
.is-list .project-details { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px 24px; align-items: center; }
.is-list .project-title, .is-list .project-synopsis, .is-list .project-progress, .is-list .project-meta { grid-column: 1; }
.is-list .project-synopsis { margin: 0; height: auto; -webkit-line-clamp: 1; }
.is-list .project-meta { padding: 0; justify-content: flex-start; gap: 20px; }
.is-list .project-actions { grid-column: 2; grid-row: 1 / 5; border: 0; padding: 0; }
@media (max-width: 1100px) { .is-list .library-project { grid-template-columns: 150px minmax(0, 1fr); } .is-list .project-details { display: block; } .is-list .project-meta { padding: 10px 0; } .is-list .project-actions { padding-top: 8px; } }
@media (max-width: 700px) { .library-search { width: 100%; } .library-view { margin-left: 0; } .is-list .library-project { grid-template-columns: minmax(0, 1fr); } .is-list .library-cover { aspect-ratio: 16 / 9; height: auto; max-height: 180px; } }
</style>
