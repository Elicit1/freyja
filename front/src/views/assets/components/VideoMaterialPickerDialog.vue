<template>
  <el-dialog v-model="visible" title="选择视频素材" width="1000px" append-to-body destroy-on-close :close-on-click-modal="false">
    <VideoList v-if="visible" selection :drama-id="dramaId" @selected="select" />
  </el-dialog>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import VideoList from './VideoList.vue'
import type { ResVideo } from '@/types/resource'
const emit = defineEmits<{ (e: 'selected', value: ResVideo): void }>()
const visible = ref(false), dramaId = ref<string | number>()
function open(id?: string | number) { dramaId.value = id; visible.value = true }
function select(value: ResVideo) { emit('selected', value); visible.value = false }
defineExpose({ open })
</script>
