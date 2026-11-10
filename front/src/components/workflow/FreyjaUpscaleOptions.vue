<template>
  <div class="upscale-options">
    <div class="fields">
      <label>二采方式<el-select :model-value="modelValue.upscaleEngine" :disabled="disabled" @update:model-value="patch('upscaleEngine', $event)"><el-option value="FREYJA" label="FREYJA 潜空间二采" :disabled="!capability?.supported" /><el-option value="PIXEL" label="像素放大后重绘（兼容已有任务）" /></el-select></label>
      <template v-if="modelValue.upscaleEngine === 'FREYJA'">
        <label>输出尺寸<el-select :model-value="modelValue.upscaleOutput" :disabled="disabled" @update:model-value="patch('upscaleOutput', $event)"><el-option value="FULL_HD" label="1080P（横屏 / 竖屏）" /><el-option value="DOUBLE" label="宽高各两倍" /></el-select></label>
        <label>潜空间放大<el-select :model-value="modelValue.upscaleMethod" :disabled="disabled" @update:model-value="patch('upscaleMethod', $event)"><el-option value="LATENT_BICUBIC" label="内置插值（无需额外模型）" /><el-option value="H3_LEARNED" label="H3 学习式放大模型" /></el-select></label>
        <label>高清采样步数<el-input-number :model-value="modelValue.upscaleSteps" :min="1" :max="capability?.maxSteps || 8" :step="1" :disabled="disabled" @update:model-value="patch('upscaleSteps', $event ?? 2)" /></label>
        <label>显存处理<el-select :model-value="modelValue.upscaleMemory" :disabled="disabled" @update:model-value="patch('upscaleMemory', $event)"><el-option value="TEMPORAL" label="原生时间分窗" /><el-option value="SPATIAL" label="FREYJA 空间分块（实验）" /><el-option value="FULL" label="整段整帧采样" /></el-select></label>
      </template>
    </div>
    <template v-if="modelValue.upscaleEngine === 'FREYJA'">
      <label v-if="modelValue.upscaleMethod === 'H3_LEARNED'" class="weight">H3 latent 模型文件<el-input :model-value="modelValue.upscaleLatentModel" :disabled="disabled" placeholder="已安装在 latent_upscale_models 中的文件名" @update:model-value="patch('upscaleLatentModel', $event.trim() || undefined)" /></label>
      <div v-if="modelValue.upscaleMemory === 'SPATIAL'" class="fields">
        <label>块宽度（像素）<el-input-number :model-value="tiles.tileWidth" :min="256" :max="2048" :step="32" step-strictly :disabled="disabled" @update:model-value="tile('tileWidth', $event ?? 768)" /></label>
        <label>块高度（像素）<el-input-number :model-value="tiles.tileHeight" :min="256" :max="2048" :step="32" step-strictly :disabled="disabled" @update:model-value="tile('tileHeight', $event ?? 512)" /></label>
        <label>重叠宽度（像素）<el-input-number :model-value="tiles.overlap" :min="32" :max="Math.min(512, tiles.tileWidth-32, tiles.tileHeight-32)" :step="32" step-strictly :disabled="disabled" @update:model-value="tile('overlap', $event ?? 128)" /></label>
      </div>
      <div v-if="modelValue.upscaleMemory === 'TEMPORAL'" class="fields">
        <label>每窗帧数<el-select :model-value="tiles.windowFrames" :disabled="disabled" @update:model-value="tile('windowFrames', $event)"><el-option v-for="n in [39,56,73,90,107,124]" :key="n" :value="n" :label="`${n} 帧`" /></el-select></label>
        <label>重叠帧数<el-select :model-value="tiles.overlapFrames" :disabled="disabled" @update:model-value="tile('overlapFrames', $event)"><el-option v-for="n in [5,22,39,56,73,90,107].filter(n => n < tiles.windowFrames)" :key="n" :value="n" :label="`${n} 帧`" /></el-select></label>
      </div>
      <p>独立二采：按来源尺寸编码，再在潜空间放大和高清重绘。接续使用一采结果。16GB 可优先尝试时间分窗；空间分块可能产生接缝，显存和画质仍需实测。</p>
    </template>
    <p v-else-if="tiles.enabled" role="alert">此任务使用的旧第三方分块方案已移除，请重新选择二采方式。</p>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { defaultUpscaleTiling, type WorkflowOptions, type WorkflowCapability, type UpscaleTilingOptions } from '@/api/video-enhancements'
const props = defineProps<{ modelValue: WorkflowOptions; disabled?: boolean; capability?: WorkflowCapability['freyjaUpscale'] }>()
const emit = defineEmits<{ 'update:modelValue': [value: WorkflowOptions] }>()
const tiles = computed(() => ({ ...defaultUpscaleTiling(), ...props.modelValue.upscaleTiling }))
function patch<K extends keyof WorkflowOptions>(key: K, value: WorkflowOptions[K]) {
  const next = { ...props.modelValue, [key]: value }
  if (key === 'upscaleEngine' && value === 'PIXEL') { next.upscaleOutput = 'DOUBLE'; next.upscaleTiling = { ...tiles.value, enabled: false } }
  emit('update:modelValue', next)
}
function tile<K extends keyof UpscaleTilingOptions>(key: K, value: UpscaleTilingOptions[K]) {
  const next = { ...tiles.value, [key]: value }
  next.overlap = Math.min(next.overlap, next.tileWidth-32, next.tileHeight-32)
  if (next.overlapFrames >= next.windowFrames) next.overlapFrames = 22
  patch('upscaleTiling', next)
}
</script>
<style scoped>
.upscale-options{display:grid;gap:12px;padding:14px;margin-bottom:16px;border:1px solid var(--border-default);border-radius:var(--radius-sm);background:var(--surface-muted);color:var(--text-primary)}.fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}label{display:flex;flex-direction:column;gap:6px;font-size:12px}.el-select{width:100%}p{margin:0;font-size:12px;line-height:1.7;color:var(--text-secondary)}
</style>
