<template>
  <span v-if="!unavailable" class="agent-media" role="img" :aria-label="label || pet.displayName" :style="style"
    :data-pet-row="row" :data-pet-frame="frame" :data-pet-columns="source.columns" :data-pet-smooth="source.smooth" />
  <el-icon v-else class="agent-media-fallback"><ChatDotRound /></el-icon>
</template>
<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import type { AgentAvatar, AvatarState } from '@/types/agent'
import { freyjaDefaultPet, avatarRows, petAnimationSource, petFrameAt } from './avatarPresets'
const props = defineProps<{ avatar?: AgentAvatar; state: AvatarState; label?: string; animationRow?: number }>()
const frame = ref(0), broken = ref(false), unavailable = ref(false)
const smoothUnavailable = ref(false), ready = ref(false)
const pet = computed(() => broken.value ? freyjaDefaultPet : props.avatar?.pet || freyjaDefaultPet)
const row = computed(() => Math.max(0, Math.min(8, props.animationRow ?? avatarRows[props.state])))
const source = computed(() => petAnimationSource(pet.value, !smoothUnavailable.value))
const style = computed(() => ({
  backgroundImage: `url(${JSON.stringify(source.value.spritesheetUrl)})`,
  backgroundSize: `${source.value.columns * 100}% ${source.value.rows * 100}%`,
  backgroundPosition: `${frame.value / (source.value.columns - 1) * 100}% ${row.value / (source.value.rows - 1) * 100}%`
}))
let animationFrame: number | undefined
let elapsed = 0, startedAt = 0
let loader: HTMLImageElement | undefined
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
function stopPlayback() {
  if (animationFrame !== undefined) {
    elapsed += performance.now() - startedAt
    cancelAnimationFrame(animationFrame); animationFrame = undefined
  }
}
function startPlayback() {
  if (animationFrame !== undefined || reduced.matches || !ready.value || unavailable.value || document.hidden) return
  startedAt = performance.now()
  const animation = source.value.animations[row.value]!
  function tick(time: number) {
    frame.value = petFrameAt(animation, elapsed + time - startedAt)
    animationFrame = requestAnimationFrame(tick)
  }
  animationFrame = requestAnimationFrame(tick)
}
function animate() { stopPlayback(); elapsed = 0; frame.value = 0; startPlayback() }
function visibilityChanged() { if (document.hidden) stopPlayback(); else startPlayback() }
watch(() => props.avatar?.pet?.spritesheetUrl, () => { broken.value = false; smoothUnavailable.value = false; unavailable.value = false })
watch(() => source.value.spritesheetUrl, url => {
  if (loader) { loader.onload = null; loader.onerror = null }
  ready.value = false
  loader = new Image()
  loader.onload = () => { unavailable.value = false; ready.value = true }
  loader.onerror = () => {
    if (source.value.smooth) smoothUnavailable.value = true
    else if (url === freyjaDefaultPet.spritesheetUrl) unavailable.value = true
    else broken.value = true
  }
  loader.src = url
}, { immediate: true })
watch([row, () => source.value.spritesheetUrl, ready, unavailable], animate, { immediate: true })
reduced.addEventListener('change', animate)
document.addEventListener('visibilitychange', visibilityChanged)
onBeforeUnmount(() => {
  stopPlayback(); reduced.removeEventListener('change', animate); document.removeEventListener('visibilitychange', visibilityChanged)
  if (loader) { loader.onload = null; loader.onerror = null }
})
</script>
<style scoped>
.agent-media { width: 100%; height: 100%; display: block; background-repeat: no-repeat; }
.agent-media-fallback { width: 100%; height: 100%; color: var(--brand); font-size: 40px; }
</style>
