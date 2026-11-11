<template>
  <section class="render-preview" aria-label="镜头预览">
    <div class="preview-toolbar">
      <div class="preview-tabs" role="tablist" aria-label="预览内容" @keydown.left.prevent="moveTab" @keydown.right.prevent="moveTab">
        <button id="render-reference-tab" type="button" role="tab" :tabindex="tab === 'references' ? 0 : -1" :aria-selected="tab === 'references'" aria-controls="render-preview-panel" :class="{ active: tab === 'references' }" @click="tab = 'references'">参考素材 <span>{{ continuation ? '分段' : referenceCount }}</span></button>
        <button id="render-result-tab" type="button" role="tab" :tabindex="tab === 'result' ? 0 : -1" :aria-selected="tab === 'result'" aria-controls="render-preview-panel" :class="{ active: tab === 'result' }" @click="tab = 'result'">已有成片 <span v-if="shot?.videoUrl" class="result-dot"></span></button>
      </div>
      <span class="duration-label"><el-icon><Clock /></el-icon>{{ shot?.duration || 0 }} 秒</span>
    </div>

    <div id="render-preview-panel" role="tabpanel" :aria-labelledby="tab === 'references' ? 'render-reference-tab' : 'render-result-tab'" class="preview-panel">
      <div class="preview-monitor">
        <template v-if="tab === 'result' && shot?.videoUrl">
          <video :key="shot.videoUrl" :src="shot.videoUrl" controls playsinline preload="metadata" aria-label="当前镜头成片" />
        </template>
        <template v-else-if="tab === 'references' && !continuation && selected?.url">
          <el-image v-if="selected.kind === 'image'" :key="selected.key" :src="selected.url" fit="contain" :preview-src-list="imageUrls" :initial-index="Math.max(0, imageUrls.indexOf(selected.url))" preview-teleported :alt="selected.label">
            <template #error><div class="monitor-empty"><el-icon><Picture /></el-icon><strong>暂时无法预览这张图片</strong><p>请在镜头中检查素材链接。</p></div></template>
          </el-image>
          <video v-else-if="selected.kind === 'video'" :key="selected.key" :src="selected.url" controls playsinline preload="metadata" :aria-label="selected.label" />
          <div v-else class="audio-preview"><span class="audio-symbol"><el-icon><Headset /></el-icon></span><strong>{{ selected.label }}</strong><audio :key="selected.key" :src="selected.url" controls preload="metadata" :aria-label="selected.label" /></div>
        </template>
        <div v-else class="monitor-empty">
          <span class="monitor-empty-icon"><el-icon><component :is="tab === 'result' ? VideoCamera : continuation ? Connection : Film" /></el-icon></span>
          <strong>{{ tab === 'result' ? '让第一个镜头，从这里开始' : continuation ? `${segmentCount} 个片段，连成一个镜头` : selected?.kind === 'video' ? '已关联参考视频' : '从想象，到画面' }}</strong>
          <p>{{ tab === 'result' ? '生成完成后，可在这里预览成片与切换历史版本。' : continuation ? '使用各片段已保存的提示词与素材，依次接续生成。' : selected?.kind === 'video' ? '提交时将使用镜头中选定的视频来源。' : '没有参考素材也可以生成。准备好提示词，就能开始。' }}</p>
        </div>
        <span v-if="tab === 'references' && selected?.url && selected.kind === 'image' && !continuation" class="monitor-caption"><span></span>{{ selected.label }}</span>
      </div>
      <div class="monitor-meta"><span><el-icon><component :is="tab === 'result' ? VideoCamera : Picture" /></el-icon>{{ tab === 'result' ? '当前选用版本' : continuation ? '片段接续预览' : '参考素材预览' }}</span><span>{{ tab === 'result' ? shot?.videoUrl ? '已归档成片' : '等待生成' : continuation ? '各片段独立配置' : selected?.kind === 'image' && selected.url ? '原始素材 · 点击图片可放大' : referenceCount ? '使用镜头中保存的素材' : '支持纯文本生成' }}</span></div>
    </div>

    <div v-if="tab === 'references' && !continuation" class="reference-shelf">
      <div class="shelf-heading"><strong>{{ isReference ? '本镜参考' : '首尾帧参考' }}</strong><button type="button" :disabled="disabled" @click="emit('edit')">管理素材<el-icon><ArrowRight /></el-icon></button></div>
      <div :class="['media-shelf', { 'frame-shelf': !isReference }]">
        <button v-for="(item, index) in media" :key="item.key" type="button" :class="['media-tile', { selected: selectedIndex === index, empty: !item.url }]" :aria-pressed="selectedIndex === index" :aria-label="`预览${item.label}`" @click="selectedIndex = index">
          <span class="media-thumb"><el-image v-if="item.url && item.kind === 'image'" :src="item.url" fit="cover" :alt="item.label" /><el-icon v-else><component :is="item.kind === 'video' ? VideoCamera : item.kind === 'audio' ? Headset : Picture" /></el-icon><span v-if="!item.url && item.kind === 'image'" class="optional-label">可选</span></span>
          <span class="media-label">{{ item.label }}</span><el-icon v-if="selectedIndex === index" class="media-selected"><Check /></el-icon>
        </button>
      </div>
      <p v-if="isReference && !media.length" class="shelf-note">还没有添加参考素材，可以直接根据提示词生成。</p>
      <p v-else-if="!isReference" class="shelf-note">首帧确定起点，尾帧引导落点。两者均为可选。</p>
    </div>

    <div v-if="continuation" class="continuation-context"><span class="context-icon"><el-icon><Connection /></el-icon></span><div><strong>沿用片段编排</strong><p>每个片段的剧本、提示词和素材来自接续工作台。</p></div><span>{{ segmentCount }} 段</span></div>

    <el-collapse v-if="prompt && !continuation" class="prompt-review">
      <el-collapse-item name="prompt"><template #title><span class="prompt-heading"><el-icon><Document /></el-icon>镜头提示词<span>{{ prompt.length }} 字符</span></span></template><p class="prompt-text">{{ prompt }}</p></el-collapse-item>
    </el-collapse>
    <button v-if="shot?.id" type="button" class="history-link" :disabled="disabled" @click="emit('history')"><el-icon><Clock /></el-icon><span>视频历史与版本重选</span><el-icon><ArrowRight /></el-icon></button>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowRight, Check, Clock, Connection, Document, Film, Headset, Picture, VideoCamera } from '@element-plus/icons-vue'
import type { DramaShot } from '@/types/drama'

const props = defineProps<{ shot?: DramaShot; continuation: boolean; segmentCount: number; disabled?: boolean }>()
const emit = defineEmits<{ edit: []; history: [] }>()
type PreviewMedia = { key: string; label: string; kind: 'image' | 'video' | 'audio'; url?: string }
const tab = ref<'references' | 'result'>('references'), selectedIndex = ref(0)
const isReference = computed(() => props.shot?.generationMode === 'REFERENCE_MODE')
const prompt = computed(() => isReference.value ? props.shot?.prompt : props.shot?.videoPrompt || props.shot?.prompt)
const media = computed<PreviewMedia[]>(() => {
  if (!isReference.value) return [
    { key: 'first', label: '首帧', kind: 'image', url: props.shot?.previewImageUrl },
    { key: 'last', label: '尾帧', kind: 'image', url: props.shot?.endFrameImageUrl }
  ]
  return [
    ...(props.shot?.refImages || []).map((item, i) => ({ key: `image-${i}`, label: item.name || `参考图 ${i + 1}`, kind: 'image' as const, url: item.imageUrl })),
    ...(props.shot?.refVideos || []).map((item, i) => ({ key: `video-${i}`, label: item.name || `参考视频 ${i + 1}`, kind: 'video' as const, url: item.sourceVideoUrl ? `${item.sourceVideoUrl.split('#')[0]}#t=${item.startSeconds || 0}${item.endSeconds != null ? `,${item.endSeconds}` : ''}` : undefined })),
    ...(props.shot?.refAudios || []).map((item, i) => ({ key: `audio-${i}`, label: item.name || `参考音频 ${i + 1}`, kind: 'audio' as const, url: item.audioUrl }))
  ]
})
const selected = computed(() => media.value[selectedIndex.value])
const referenceCount = computed(() => isReference.value ? media.value.length : media.value.filter(item => item.url).length)
const imageUrls = computed(() => media.value.filter(item => item.kind === 'image' && item.url).map(item => item.url!))
watch(() => props.shot, () => { selectedIndex.value = Math.max(0, media.value.findIndex(item => item.url)); tab.value = 'references' }, { immediate: true })
watch(() => props.shot?.videoUrl, (url, before) => { if (url && url !== before) tab.value = 'result' })
async function moveTab(event: KeyboardEvent) {
  const tabs = event.currentTarget as HTMLElement
  tab.value = tab.value === 'references' ? 'result' : 'references'
  await nextTick()
  tabs.querySelector<HTMLButtonElement>('[aria-selected="true"]')?.focus()
}
</script>

<style scoped>
.render-preview { display: flex; flex-direction: column; gap: 20px; min-width: 0; }
.preview-toolbar, .monitor-meta, .shelf-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.preview-tabs { display: flex; align-items: center; gap: 22px; }
.preview-tabs button { display: flex; align-items: center; gap: 7px; padding: 0 0 10px; border: 0; border-bottom: 2px solid transparent; background: none; font-size: 13px; font-weight: 500; color: var(--text-secondary); cursor: pointer; }
.preview-tabs button.active { border-bottom-color: var(--brand); color: var(--text-primary); }
.preview-tabs button > span:not(.result-dot) { padding: 1px 5px; border-radius: 4px; background: var(--surface-hover); color: var(--text-muted); font-size: 10px; }
.preview-tabs .result-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--success); }
.duration-label { display: flex; align-items: center; gap: 5px; padding-bottom: 10px; font-size: 11px; color: var(--text-muted); font-variant-numeric: tabular-nums; }
.preview-panel { margin-top: -8px; overflow: hidden; border: 1px solid var(--border-default); border-radius: 12px; background: var(--surface); }
.preview-monitor { position: relative; display: grid; place-items: center; width: 100%; aspect-ratio: 16 / 10; overflow: hidden; background: #101218; color: #e5e7ef; }
.preview-monitor > .el-image, .preview-monitor > video { width: 100%; height: 100%; min-height: 0; object-fit: contain; }
.preview-monitor :deep(.el-image__inner) { object-fit: contain; }
.monitor-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 13px; padding: 24px; text-align: center; width: 100%; height: 100%; }
.monitor-empty-icon { display: grid; place-items: center; width: 62px; height: 62px; margin-bottom: 5px; border: 1px solid #363945; border-radius: 18px; background: #1c1f28; color: #acb0c2; font-size: 27px; }
.monitor-empty strong { font-size: 15px; font-weight: 500; }
.monitor-empty p { max-width: 300px; margin: 0; font-size: 12px; line-height: 1.8; color: #a5aab9; }
.monitor-caption { position: absolute; left: 14px; bottom: 14px; display: flex; align-items: center; gap: 7px; max-width: calc(100% - 28px); padding: 6px 10px; border: 1px solid #ffffff24; border-radius: 6px; background: #101218b8; backdrop-filter: blur(8px); font-size: 10px; }
.monitor-caption > span { width: 5px; height: 5px; border-radius: 50%; background: #c4b5fd; }
.monitor-meta { padding: 11px 13px; font-size: 10px; color: var(--text-muted); }
.monitor-meta > span:first-child { display: flex; align-items: center; gap: 6px; color: var(--text-secondary); }
.reference-shelf { display: grid; gap: 12px; }
.shelf-heading strong { font-size: 12px; font-weight: 600; color: var(--text-primary); }
.shelf-heading button { display: flex; align-items: center; gap: 4px; padding: 0; border: 0; background: none; color: var(--text-secondary); font-size: 11px; cursor: pointer; }
.shelf-heading button:hover { color: var(--brand); }
.media-shelf { display: flex; gap: 10px; overflow-x: auto; padding: 2px; }
.media-tile { position: relative; flex: 0 0 100px; min-width: 0; padding: 5px; border: 1px solid var(--border-default); border-radius: 9px; background: var(--surface); color: var(--text-secondary); cursor: pointer; text-align: left; transition: border-color .15s, background .15s; }
.media-tile:hover { border-color: var(--border-strong); }
.media-tile.selected { border-color: var(--brand); background: var(--brand-soft); }
.media-thumb { display: flex; align-items: center; justify-content: center; gap: 7px; width: 100%; aspect-ratio: 16 / 9; overflow: hidden; border-radius: 5px; background: var(--surface-muted); color: var(--text-muted); font-size: 20px; }
.media-thumb .el-image { width: 100%; height: 100%; }
.media-label { display: block; padding: 7px 3px 3px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 10px; }
.media-selected { position: absolute; top: 9px; right: 9px; padding: 2px; border-radius: 50%; background: var(--brand); color: white; font-size: 10px; }
.frame-shelf { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.frame-shelf .media-tile { padding: 6px; }
.frame-shelf .media-thumb { max-height: 96px; }
.empty .media-thumb { border: 1px dashed var(--border-strong); }
.optional-label { font-size: 11px; }
.shelf-note { margin: 0; font-size: 11px; line-height: 1.7; color: var(--text-muted); }
.prompt-review { --el-collapse-border-color: var(--border-default); border-bottom: 0; }
.prompt-review :deep(.el-collapse-item__header), .prompt-review :deep(.el-collapse-item__wrap) { background: transparent; }
.prompt-review :deep(.el-collapse-item__header) { height: 44px; border-bottom: 0; }
.prompt-review :deep(.el-collapse-item__content) { padding: 0 0 12px; }
.prompt-heading { display: flex; align-items: center; gap: 8px; width: 100%; font-size: 12px; font-weight: 500; color: var(--text-primary); }
.prompt-heading > span { margin-left: auto; margin-right: 12px; color: var(--text-muted); font-size: 10px; font-weight: 400; }
.prompt-text { max-height: 170px; margin: 0; overflow-y: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; line-height: 1.8; color: var(--text-secondary); }
.history-link { display: flex; align-items: center; gap: 8px; align-self: flex-start; padding: 0; border: 0; background: none; color: var(--text-secondary); font-size: 11px; cursor: pointer; }
.history-link > .el-icon:last-child { margin-left: 6px; }
.history-link:hover { color: var(--brand); }
.audio-preview { display: flex; flex-direction: column; align-items: center; gap: 22px; width: 100%; padding: 28px; }
.audio-symbol { display: grid; place-items: center; width: 64px; height: 64px; border-radius: 50%; background: #ffffff10; font-size: 28px; }
.audio-preview > strong { font-size: 13px; }
.audio-preview > audio { max-width: 100%; }
.continuation-context { display: flex; align-items: center; gap: 12px; padding: 16px; border: 1px solid var(--border-default); border-radius: 10px; background: var(--surface); }
.context-icon { display: grid; place-items: center; width: 34px; height: 34px; flex-shrink: 0; border-radius: 9px; background: var(--brand-soft); color: var(--brand); }
.continuation-context strong { font-size: 12px; color: var(--text-primary); }
.continuation-context p { margin: 5px 0 0; font-size: 11px; line-height: 1.7; color: var(--text-secondary); }
.continuation-context > span:last-child { margin-left: auto; white-space: nowrap; color: var(--text-muted); font-size: 11px; }
button:disabled { cursor: not-allowed; opacity: .5; }
button:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
@media(max-width: 600px) { .preview-toolbar { gap: 8px; }.preview-tabs { gap: 16px; }.preview-tabs button { font-size: 12px; }.monitor-meta { font-size: 9px; gap: 8px; }.monitor-empty { gap: 9px; padding: 16px; }.monitor-empty-icon { width: 44px; height: 44px; border-radius: 13px; font-size: 22px; }.monitor-empty strong { font-size: 13px; }.monitor-empty p { font-size: 11px; } }
</style>
