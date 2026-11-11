<template>
  <div class="upscale-options">
    <div class="fields primary-fields">
      <template v-if="modelValue.upscaleEngine === 'FREYJA'">
        <label>二采方式<el-select :model-value="modelValue.upscaleSampler" :disabled="disabled" @update:model-value="patch('upscaleSampler', $event)"><el-option value="STANDARD" label="标准二采" /><el-option value="SELFLIFT" label="SelfLift 二采" :disabled="!capability?.samplers?.includes('SELFLIFT')" /></el-select></label>
        <label>输出尺寸<el-select :model-value="modelValue.upscaleOutput" :disabled="disabled || preserveSize" @update:model-value="patch('upscaleOutput', $event)"><el-option value="FULL_HD" label="1080P（横屏 / 竖屏）" /><el-option value="DOUBLE" label="宽高各两倍" /></el-select></label>
        <label>高清重绘步数<el-input-number :model-value="modelValue.upscaleSteps" :min="1" :max="capability?.maxSteps || 8" :step="1" :disabled="disabled" @update:model-value="patch('upscaleSteps', $event ?? 2)" /></label>
      </template>
    </div>
    <p v-if="modelValue.upscaleSampler === 'SELFLIFT'">SelfLift-zero 使用像素重编码与 latent 放大的差异修正高风险区域，再进行高清采样。人脸修复仍在二采完成后执行。</p>
    <template v-if="modelValue.upscaleEngine === 'FREYJA'">
      <div class="memory-settings">
        <strong>显存使用方式</strong>
        <el-radio-group :model-value="modelValue.upscaleMemory" :disabled="disabled" aria-label="显存使用方式" @update:model-value="patch('upscaleMemory', $event)">
          <el-radio v-for="mode in memoryModes" :key="mode.value" :value="mode.value" border><span class="memory-choice"><strong>{{ mode.label }}<span class="memory-badge">{{ mode.hint }}</span></strong><span>{{ mode.description }}</span></span></el-radio>
        </el-radio-group>
        <p>不确定时保持推荐设置。实际显存占用还取决于视频长度、分辨率和模型。</p>
      </div>
      <el-collapse v-if="modelValue.upscaleMemory !== 'FULL'"><el-collapse-item title="高级显存参数（通常无需修改）" name="memory">
        <div v-if="modelValue.upscaleMemory === 'SPATIAL'" class="fields">
          <label>每块宽度（像素）<el-input-number :model-value="tiles.tileWidth" :min="256" :max="2048" :step="32" step-strictly :disabled="disabled" @update:model-value="tile('tileWidth', $event ?? 768)" /></label>
          <label>每块高度（像素）<el-input-number :model-value="tiles.tileHeight" :min="256" :max="2048" :step="32" step-strictly :disabled="disabled" @update:model-value="tile('tileHeight', $event ?? 512)" /></label>
          <label>相邻块重叠宽度（像素）<el-input-number :model-value="tiles.overlap" :min="32" :max="Math.min(512, tiles.tileWidth-32, tiles.tileHeight-32)" :step="32" step-strictly :disabled="disabled" @update:model-value="tile('overlap', $event ?? 192)" /></label>
        </div>
        <div v-else class="fields">
          <label>每次处理的时间范围<el-select :model-value="tiles.windowFrames" :disabled="disabled" @update:model-value="tile('windowFrames', $event)"><el-option v-for="n in [39,56,73,90,107,124]" :key="n" :value="n" :label="`${n} 帧（约 ${(n / 24).toFixed(1)} 秒）`" /></el-select></label>
          <label>相邻时间窗的重叠范围<el-select :model-value="tiles.overlapFrames" :disabled="disabled" @update:model-value="tile('overlapFrames', $event)"><el-option v-for="n in [5,22,39,56,73,90,107].filter(n => n < tiles.windowFrames)" :key="n" :value="n" :label="`${n} 帧（约 ${(n / 24).toFixed(1)} 秒）`" /></el-select></label>
        </div>
      </el-collapse-item></el-collapse>
    </template>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { defaultUpscaleTiling, normalizeWorkflowOptions, type WorkflowOptions, type WorkflowCapability, type UpscaleTilingOptions } from '@/api/video-enhancements'
const props = defineProps<{ modelValue: WorkflowOptions; disabled?: boolean; preserveSize?: boolean; capability?: WorkflowCapability['freyjaUpscale'] }>()
const emit = defineEmits<{ 'update:modelValue': [value: WorkflowOptions] }>()
const tiles = computed(() => ({ ...defaultUpscaleTiling(), ...props.modelValue.upscaleTiling }))
const memoryModes: Array<{ value: WorkflowOptions['upscaleMemory']; label: string; hint: string; description: string }> = [
  { value: 'TEMPORAL', label: '按时间分窗', hint: '推荐', description: '分段处理时间，适合较长视频。更省显存，可能影响运动连续性。' },
  { value: 'SPATIAL', label: '按画面分块', hint: '实验', description: '分块处理画面，适合高分辨率。可能更慢，或出现画面接缝。' },
  { value: 'FULL', label: '完整处理', hint: '显存充足', description: '整段、全画面一次处理，显存压力最大。可用于对比分窗、分块效果。' }
]
function patch<K extends keyof WorkflowOptions>(key: K, value: WorkflowOptions[K]) {
  if (props.disabled) return
  const next = normalizeWorkflowOptions({ ...props.modelValue, [key]: value })
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
.upscale-options {
  container-type: inline-size;
  display: grid;
  gap: 20px;
  padding: 16px;
  margin-bottom: 16px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  background: var(--surface-raised);
  color: var(--text-primary);
}
.fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; }
.fields > label { display: flex; flex-direction: column; gap: 8px; min-width: 0; font-size: 12px; }
.fields .el-select, .fields .el-input-number { width: 100%; }
p { margin: 0; font-size: 12px; line-height: 1.7; color: var(--text-secondary); }
.memory-settings { display: grid; gap: 10px; }
.memory-settings > strong { font-size: 12px; font-weight: 500; }
.memory-settings .el-radio-group { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.memory-settings .el-radio { display: flex; flex-direction: row; align-items: flex-start; margin: 0; height: auto; min-width: 0; padding: 14px 12px; white-space: normal; }
.memory-settings .el-radio.is-checked { background: var(--brand-soft); }
.memory-settings :deep(.el-radio__input) { margin-top: 2px; }
.memory-settings :deep(.el-radio__label) { min-width: 0; padding-left: 8px; white-space: normal; }
.memory-choice { display: grid; gap: 8px; text-align: left; line-height: 1.6; }
.memory-choice strong { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 13px; font-weight: 500; color: var(--text-primary); }
.memory-badge { padding: 0 5px; border-radius: 4px; background: var(--surface-muted); font-size: 10px; font-weight: 400; color: var(--text-secondary); }
.is-checked .memory-badge { color: var(--brand); background: var(--surface-raised); }
.memory-choice > span { font-size: 12px; color: var(--text-secondary); }
.upscale-options :deep(.el-collapse) { border-bottom: 0; border-top: 1px solid var(--border-default); }
.upscale-options :deep(.el-collapse-item__header) { height: auto; min-height: 40px; border: 0; background: transparent; font-size: 12px; font-weight: 400; color: var(--text-secondary); }
.upscale-options :deep(.el-collapse-item__wrap) { border: 0; background: transparent; }
.upscale-options :deep(.el-collapse-item__content) { padding: 4px 0 0; }
@container (min-width: 740px) {
  .primary-fields { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@container (max-width: 620px) {
  .memory-settings .el-radio-group { grid-template-columns: 1fr; }
  .memory-settings .el-radio { padding: 12px; }
  .memory-choice { gap: 4px; }
}
@container (max-width: 380px) {
  .fields { grid-template-columns: 1fr; }
}
</style>
