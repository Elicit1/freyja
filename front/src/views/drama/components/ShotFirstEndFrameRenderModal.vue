<template>
  <ShotVideoRenderDialog ref="dialog" @success="id => emit('success', id)" @edit="shot => emit('edit', shot)" />
</template>
<script setup lang="ts">
import { ref } from 'vue'
import type { DramaShot } from '@/types/drama'
import ShotVideoRenderDialog from './ShotVideoRenderDialog.vue'
const dialog = ref<InstanceType<typeof ShotVideoRenderDialog>>()
const emit = defineEmits<{ success: [shotId: string | number]; edit: [shot: DramaShot] }>()
function open(shot: DramaShot, _previousTail?: string, aspectRatio?: string) { return dialog.value?.open(shot, aspectRatio) }
defineExpose({ open })
</script>
