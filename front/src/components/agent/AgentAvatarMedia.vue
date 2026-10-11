<template>
  <span v-if="!unavailable" class="agent-media" role="img" :aria-label="label || pet.displayName" :style="style" :data-pet-row="row" :data-pet-frame="frame" />
  <el-icon v-else class="agent-media-fallback"><ChatDotRound /></el-icon>
</template>
<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import type { AgentAvatar, AvatarState } from '@/types/agent'
import { freyjaDefaultPet, petRows, avatarRows, petPlaybackRate } from './avatarPresets'
const props = defineProps<{ avatar?: AgentAvatar; state: AvatarState; label?: string; animationRow?: number }>()
const frame = ref(0), broken = ref(false), unavailable = ref(false)
const pet = computed(() => broken.value ? freyjaDefaultPet : props.avatar?.pet || freyjaDefaultPet)
const row = computed(() => props.animationRow ?? avatarRows[props.state])
const style = computed(() => ({
  backgroundImage: `url(${JSON.stringify(pet.value.spritesheetUrl)})`,
  backgroundSize: `800% ${pet.value.rows * 100}%`,
  backgroundPosition: `${frame.value / 7 * 100}% ${row.value / (pet.value.rows - 1) * 100}%`
}))
let timer: ReturnType<typeof setTimeout> | undefined
let loader: HTMLImageElement | undefined
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
function animate() {
  clearTimeout(timer); frame.value = 0
  const animation = petRows[row.value] || petRows[0]!
  const durations = animation.durations
  if (reduced.matches || unavailable.value) return
  function step() {
    const pause = frame.value === durations.length - 1 ? animation.loopPauseMs || 0 : 0
    timer = setTimeout(() => { frame.value = (frame.value + 1) % durations.length; step() }, durations[frame.value]! / petPlaybackRate + pause)
  }
  step()
}
watch(() => props.avatar?.pet?.spritesheetUrl, () => { broken.value = false; unavailable.value = false })
watch(() => pet.value.spritesheetUrl, url => {
  if (loader) { loader.onload = null; loader.onerror = null }
  loader = new Image()
  loader.onload = () => { unavailable.value = false }
  loader.onerror = () => { if (url === freyjaDefaultPet.spritesheetUrl) unavailable.value = true; else broken.value = true }
  loader.src = url
}, { immediate: true })
watch([row, () => pet.value.spritesheetUrl, unavailable], animate, { immediate: true })
reduced.addEventListener('change', animate)
onBeforeUnmount(() => { clearTimeout(timer); reduced.removeEventListener('change', animate); if (loader) { loader.onload = null; loader.onerror = null } })
</script>
<style scoped>
.agent-media { width: 100%; height: 100%; display: block; background-repeat: no-repeat; }
.agent-media-fallback { width: 100%; height: 100%; color: var(--brand); font-size: 40px; }
</style>
