<template>
  <details v-if="references.length" class="text-xs border border-violet-200 rounded-lg p-2 space-y-2">
    <summary class="cursor-pointer font-medium">参考视频输入（{{ references.length }} 段）</summary>
    <div v-for="(video, index) in references" :key="video.id || index" class="mt-2 space-y-1 break-all">
      <strong>Video {{ index + 1 }} · {{ video.name || '参考视频' }}</strong>
      <p>片段 {{ video.startSeconds ?? 0 }} ～ {{ video.endSeconds ?? '结尾' }} 秒 · 原音参考{{ video.includeAudio ? '开启' : '关闭' }}</p>
      <p v-if="video.sourceVideoTakeId">来源 Take：{{ video.sourceVideoTakeId }}</p>
      <p v-if="video.referenceDescription">{{ video.referenceDescription }}</p>
    </div>
  </details>
</template>

<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ json?: string }>()
interface VideoInput { id?: string; name?: string; startSeconds?: number; endSeconds?: number; includeAudio?: boolean; sourceVideoTakeId?: string; referenceDescription?: string }
const references = computed<VideoInput[]>(() => {
  if (!props.json) return []
  try { const value = JSON.parse(props.json); return Array.isArray(value) ? value : value.resolvedRefVideos || [] }
  catch { return [] }
})
</script>
