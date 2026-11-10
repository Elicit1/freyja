<template>
  <div class="upscale-options">
    <div class="fields">
      <label>二采方式<el-select :model-value="modelValue.upscaleEngine" :disabled="disabled || preserveSize" @update:model-value="patch('upscaleEngine', $event)"><el-option value="FREYJA" label="FREYJA 高清重绘" :disabled="!capability?.supported" /><el-option value="PIXEL" label="像素放大后重绘（兼容已有任务）" /></el-select></label>
      <template v-if="modelValue.upscaleEngine === 'FREYJA'">
        <label>输出尺寸<el-select :model-value="modelValue.upscaleOutput" :disabled="disabled || preserveSize" @update:model-value="patch('upscaleOutput', $event)"><el-option value="FULL_HD" label="1080P（横屏 / 竖屏）" /><el-option value="DOUBLE" label="宽高各两倍" /></el-select></label>
        <label>放大方式<el-select :model-value="modelValue.upscaleMethod" :disabled="disabled" @update:model-value="patch('upscaleMethod', $event)"><el-option value="LATENT_BICUBIC" label="内置放大（默认，无需安装模型）" /><el-option value="H3_LEARNED" label="使用已安装的放大模型" /></el-select></label>
        <label>高清重绘步数<el-input-number :model-value="modelValue.upscaleSteps" :min="1" :max="capability?.maxSteps || 8" :step="1" :disabled="disabled" @update:model-value="patch('upscaleSteps', $event ?? 2)" /></label>
      </template>
    </div>
    <template v-if="modelValue.upscaleEngine === 'FREYJA'">
      <div v-if="modelValue.upscaleMethod === 'H3_LEARNED'" class="model-picker">
        <label>放大模型
          <el-select :model-value="modelValue.upscaleLatentModel" :disabled="disabled || modelsLoading || !installedModels.length" :loading="modelsLoading" filterable placeholder="选择当前节点已安装的模型" aria-label="放大模型" @update:model-value="patch('upscaleLatentModel', $event)">
            <el-option v-if="modelValue.upscaleLatentModel && !installedModels.includes(modelValue.upscaleLatentModel)" :value="modelValue.upscaleLatentModel" :label="`${modelValue.upscaleLatentModel}（当前不可用）`" disabled />
            <el-option v-for="name in installedModels" :key="name" :value="name" :label="name" />
          </el-select>
        </label>
        <p v-if="modelsLoading" role="status">正在读取当前节点的已安装模型…</p>
        <p v-else-if="modelsError" role="alert">{{ modelsError }}</p>
        <p v-else-if="!installedModels.length" role="status">当前节点没有可选的放大模型。可使用内置放大，或由管理员配置兼容模型后刷新。</p>
        <p v-else-if="modelValue.upscaleLatentModel && !installedModels.includes(modelValue.upscaleLatentModel)" role="alert">此前使用的模型在当前节点不可用，请重新选择或改用内置放大。</p>
        <div class="model-actions"><el-button link type="primary" :disabled="disabled || modelsLoading" @click="loadModels">刷新模型列表</el-button><el-button link type="primary" :disabled="disabled" @click="patch('upscaleMethod', 'LATENT_BICUBIC')">使用内置放大</el-button></div>
      </div>
      <div class="memory-settings">
        <strong>显存使用方式</strong>
        <el-radio-group :model-value="modelValue.upscaleMemory" :disabled="disabled" aria-label="显存使用方式" @update:model-value="patch('upscaleMemory', $event)">
          <el-radio v-for="mode in memoryModes" :key="mode.value" :value="mode.value" border><span class="memory-choice"><strong>{{ mode.label }}</strong><span>{{ mode.description }}</span></span></el-radio>
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
    <p v-else-if="tiles.enabled" role="alert">此任务使用的旧第三方分块方案已移除，请重新选择二采方式。</p>
  </div>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { videoEnhancementApi, defaultUpscaleTiling, type WorkflowOptions, type WorkflowCapability, type UpscaleTilingOptions } from '@/api/video-enhancements'
const props = defineProps<{ modelValue: WorkflowOptions; disabled?: boolean; preserveSize?: boolean; capability?: WorkflowCapability['freyjaUpscale']; providerId?: string; modelCode?: string; nodeId?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: WorkflowOptions] }>()
const tiles = computed(() => ({ ...defaultUpscaleTiling(), ...props.modelValue.upscaleTiling }))
const memoryModes: Array<{ value: WorkflowOptions['upscaleMemory']; label: string; description: string }> = [
  { value: 'TEMPORAL', label: '按时间分窗 · 推荐', description: '每次处理较短的时间范围，适合较长视频；可能影响运动连续性。' },
  { value: 'SPATIAL', label: '按画面分块 · 实验功能', description: '每次处理画面的一部分，适合高分辨率时显存不足；可能更慢或出现接缝。' },
  { value: 'FULL', label: '完整处理 · 显存充足时', description: '一次处理整个片段和完整画面，显存压力最大；可用于对比排查分窗、分块带来的问题。' }
]
const installedModels = ref<string[]>([]), modelsLoading = ref(false), modelsError = ref('')
let modelRequest = 0
async function loadModels() {
  const token = ++modelRequest
  installedModels.value = []; modelsError.value = ''; modelsLoading.value = false
  if (props.modelValue.upscaleEngine !== 'FREYJA' || props.modelValue.upscaleMethod !== 'H3_LEARNED') return
  if (!props.providerId || !props.modelCode || !props.nodeId) { modelsError.value = '请先选择视频提供商和模型。'; return }
  modelsLoading.value = true
  try {
    const result = await videoEnhancementApi.upscaleModels(props.providerId, props.modelCode, props.nodeId)
    if (token !== modelRequest) return
    if (!Array.isArray(result.models) || result.models.some(name => typeof name !== 'string')) throw new Error('放大模型列表格式无效，请更新网关。')
    installedModels.value = result.models
    if (result.models.length === 1 && !props.modelValue.upscaleLatentModel) patch('upscaleLatentModel', result.models[0])
  } catch (error: any) { if (token === modelRequest) modelsError.value = error.message || '读取已安装模型失败，可改用内置放大。' }
  finally { if (token === modelRequest) modelsLoading.value = false }
}
watch([() => props.providerId, () => props.modelCode, () => props.nodeId, () => props.modelValue.upscaleEngine, () => props.modelValue.upscaleMethod], (_, __, onCleanup) => {
  void loadModels()
  onCleanup(() => { modelRequest++ })
}, { immediate: true })
function patch<K extends keyof WorkflowOptions>(key: K, value: WorkflowOptions[K]) {
  if (props.disabled) return
  const next = { ...props.modelValue, [key]: value }
  if (key === 'upscaleMethod' && value === 'LATENT_BICUBIC') next.upscaleLatentModel = undefined
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
.upscale-options{display:grid;gap:14px;padding:14px;margin-bottom:16px;border:1px solid var(--border-default);border-radius:var(--radius-sm);background:var(--surface-muted);color:var(--text-primary)}.fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}label{display:flex;flex-direction:column;gap:6px;font-size:12px}.el-select{width:100%}p{margin:0;font-size:12px;line-height:1.7;color:var(--text-secondary)}.model-picker,.memory-settings{display:grid;gap:8px}.memory-settings>strong{font-size:12px}.memory-settings .el-radio-group{display:grid;gap:8px}.memory-settings .el-radio{margin:0;height:auto;padding:12px;white-space:normal}.memory-choice{display:grid;gap:4px;line-height:1.6}.memory-choice strong{font-size:13px}.memory-choice>span{font-size:12px;color:var(--text-secondary)}.model-actions{display:flex;gap:12px;flex-wrap:wrap}
</style>
