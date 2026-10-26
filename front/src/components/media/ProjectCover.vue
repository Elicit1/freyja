<template>
  <div class="project-cover" :class="{ 'has-image': coverUrl && !failed }">
    <img v-if="coverUrl && !failed" :src="coverUrl" :alt="title + '封面'" loading="lazy" @error="failed = true" />
    <div v-else class="project-cover-placeholder" aria-hidden="true">
      <span class="cover-orbit" />
      <el-icon><Film /></el-icon>
      <span class="cover-title">{{ title }}</span>
      <span class="cover-caption">FREYJA ORIGINAL</span>
    </div>
    <slot />
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { Film } from '@element-plus/icons-vue'
const props = defineProps<{ title: string; coverUrl?: string }>()
const failed = ref(false)
watch(() => props.coverUrl, () => { failed.value = false })
</script>

<style scoped>
.project-cover { position: relative; overflow: hidden; background: var(--surface-muted); }
.project-cover > img { width: 100%; height: 100%; object-fit: cover; transition: transform .25s ease; }
.project-cover:hover > img { transform: scale(1.035); }
.project-cover-placeholder { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 24px; color: var(--brand); background: linear-gradient(140deg, var(--brand-soft), var(--surface-muted)); }
.cover-orbit { position: absolute; width: 210px; height: 210px; border: 1px solid var(--brand); border-radius: 50%; opacity: .12; transform: translate(60%, -25%); }
.cover-orbit::after { content: ''; position: absolute; inset: 20px; border: inherit; border-radius: inherit; }
.project-cover-placeholder > .el-icon { font-size: 28px; opacity: .55; }
.cover-title { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 18px; font-weight: 650; letter-spacing: .06em; }
.cover-caption { font-size: 9px; letter-spacing: .18em; opacity: .6; }
</style>
