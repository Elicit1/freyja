<template>
  <section class="continuity-selector" aria-label="接续方式">
    <div class="continuity-heading"><div><h3>{{ compact ? '本段接续方式' : '接续方式' }}</h3></div><span class="mode-note">{{ capabilities ? '按所选模型的实际能力展示' : '提交前检查所选模型的节点能力' }}</span></div>
    <div class="mode-grid" role="radiogroup" aria-label="接续方式" @keydown="navigate">
      <button v-for="item in modes" :key="item.id" type="button" role="radio" :aria-checked="value.mode === item.id" :disabled="capabilities?.[item.id]?.available === false" :title="capabilities?.[item.id]?.reason || item.detail" :class="['mode-card', { selected: value.mode === item.id }]" @click="select(item.id)">
        <span class="mode-card-heading"><strong>{{ item.label }}</strong><span class="mode-indicator" aria-hidden="true"><span v-if="value.mode === item.id" /></span></span><span>{{ item.detail }}</span><small>{{ capabilities?.[item.id]?.available === false ? capabilities[item.id]?.reason || '缺少节点' : item.tag }}</small>
      </button>
    </div>
    <div v-if="value.mode === 'GUIDE_CHROMA' && value.chroma" class="mode-settings">
      <label>彩噪强度<el-input-number :model-value="value.chroma.strength" :min="0" :max="1" :step="0.05" :precision="2" @update:model-value="patchChroma('strength', $event)" /></label>
      <label>接缝强度<el-input-number :model-value="value.chroma.seamStrength" :min="0" :max="value.chroma.strength" :step="0.05" :precision="2" @update:model-value="patchChroma('seamStrength', $event)" /></label>
      <label>尾部渐弱（帧）<el-input-number :model-value="value.chroma.taperFrames" :min="1" :max="overlapFrames || 22" :step="1" @update:model-value="patchChroma('taperFrames', $event)" /></label>
      <p>只处理临时交接副本，保留原始素材、动作特征和音频。数值属于 FREYJA 色度注噪配方。</p>
    </div>
    <div v-else-if="value.mode === 'LATENT_REDRAW' && value.redraw" class="mode-settings">
      <label>上下文重绘幅度<el-input-number :model-value="value.redraw.strength" :min="0" :max="0.95" :step="0.05" :precision="2" @update:model-value="update({ ...value, redraw: { strength: $event ?? 0.1 } })" /></label>
      <p>继承上一段原始 latent，在接缝附近受控重绘；幅度越大，越允许改变继承内容。旧任务未保存原始 latent 时需要重新生成前驱段。</p>
    </div>
    <div v-else-if="value.mode === 'LATENT_PROTECTED' && value.protection" class="mode-settings">
      <label>上下文噪声<el-input-number :model-value="value.protection.noiseStrength" :min="0" :max="1" :step="0.05" :precision="2" @update:model-value="patchProtection('noiseStrength', $event ?? 1)" /></label>
      <label class="checkbox-label"><el-checkbox :model-value="value.protection.useFirstFrameAnchor" @update:model-value="patchProtection('useFirstFrameAnchor', !!$event)">干净首帧锚定</el-checkbox></label>
      <label v-if="anchorSegments?.length">干净来源段<el-select :model-value="value.protection.anchorSourceSegmentId" clearable placeholder="本任务首段" @update:model-value="patchProtection('anchorSourceSegmentId', $event || undefined)"><el-option v-for="segment in anchorSegments" :key="segment.id" :value="segment.id" :label="segment.name || segment.id" /></el-select></label>
      <el-collapse class="advanced"><el-collapse-item title="高级：尾部锚定" name="anchor"><el-checkbox :model-value="value.protection.useTailAnchor" @update:model-value="patchProtection('useTailAnchor', !!$event)">延迟注入来源尾帧</el-checkbox><label v-if="value.protection.useTailAnchor">开始比例<el-input-number :model-value="value.protection.tailAnchorStartFraction" :min="0" :max="1" :step="0.05" @update:model-value="patchProtection('tailAnchorStartFraction', $event ?? 0.75)" /></label><p>尾锚可能牵引姿态，当前适配需关闭 ControlNet。首帧锚定和上下文噪声可以继续使用 ControlNet。</p></el-collapse-item></el-collapse>
      <p>接缝处噪声渐弱至零，使用干净来源帧帮助维持外观；需要保存原始 latent。实际画质取决于模型与采样配置。</p>
    </div>
    <p v-else class="standard-note">使用上一段的干净尾部引导下一段，沿用现有接续行为。</p>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ContinuityMode, ContinuityOptions, ContinuityCapability, ContinuationSegment } from '@/types/continuation'
const props = defineProps<{ modelValue?: ContinuityOptions; overlapFrames?: number; compact?: boolean; capabilities?: Partial<Record<ContinuityMode, ContinuityCapability>>; anchorSegments?: ContinuationSegment[] }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: ContinuityOptions): void }>()
const modes: Array<{ id: ContinuityMode; label: string; detail: string; tag: string }> = [
  { id: 'GUIDE', label: '标准引导', detail: '干净尾部衔接', tag: '兼容优先' },
  { id: 'GUIDE_CHROMA', label: '彩噪引导', detail: '渐弱彩噪引导', tag: '细节重建' },
  { id: 'LATENT_REDRAW', label: '引导＋重绘', detail: '原始上下文重绘', tag: '动作衔接' },
  { id: 'LATENT_PROTECTED', label: '抗劣化接续', detail: '上下文噪声与锚定', tag: '长镜头' }
]
const value = computed(() => props.modelValue || { mode: 'GUIDE' as const })
function update(next: ContinuityOptions) { emit('update:modelValue', next) }
function select(mode: ContinuityMode) {
  if (mode === value.value.mode) return
  const next: ContinuityOptions = { mode }
  if (mode === 'GUIDE_CHROMA') next.chroma = { strength: 0.45, seamStrength: 0.1, taperFrames: Math.min(3, props.overlapFrames || 22) }
  if (mode === 'LATENT_REDRAW') next.redraw = { strength: 0.1 }
  if (mode === 'LATENT_PROTECTED') next.protection = { noiseStrength: 1, useFirstFrameAnchor: true, useTailAnchor: false, tailAnchorStartFraction: 0.75 }
  update(next)
}
function patchChroma(key: 'strength' | 'seamStrength' | 'taperFrames', number: number | undefined) {
  if (!value.value.chroma || number === undefined) return
  const chroma = { ...value.value.chroma, [key]: number }
  chroma.seamStrength = Math.min(chroma.seamStrength, chroma.strength)
  update({ ...value.value, chroma })
}
function patchProtection(key: string, next: string | number | boolean | undefined) {
  if (value.value.protection) update({ ...value.value, protection: { ...value.value.protection, [key]: next } })
}
function navigate(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return
  const buttons = Array.from((event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
  if (index < 0 || !buttons.length) return
  event.preventDefault()
  const next = buttons[(index + (event.key === 'ArrowRight' ? 1 : buttons.length - 1)) % buttons.length]
  next?.focus(); next?.click()
}
</script>

<style scoped>
.continuity-selector{margin:20px 0 16px;color:var(--text-primary)}
.continuity-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}
h3{font-size:14px;font-weight:600;margin:0}.mode-note{font-size:11px;color:var(--text-secondary)}
.mode-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}
.mode-card{display:flex;flex-direction:column;gap:6px;min-height:100px;padding:12px;border:1px solid var(--border-default);border-radius:var(--radius-sm);text-align:left;color:var(--text-primary);background:var(--surface-muted);transition:background var(--transition-fast),border-color var(--transition-fast)}
.mode-card:hover:not(:disabled){border-color:var(--border-strong);background:var(--surface-hover)}
.mode-card:focus-visible{outline:2px solid var(--brand);outline-offset:3px}
.mode-card.selected{border-color:var(--brand);background:var(--brand-soft)}
.mode-card:disabled{opacity:.55;cursor:not-allowed}.mode-card-heading{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%}
.mode-card strong{font-size:13px}.mode-card>span:not(.mode-card-heading),.mode-card small{font-size:11px;color:var(--text-secondary)}.mode-card small{margin-top:auto}
.mode-indicator{display:grid;place-items:center;flex-shrink:0;width:14px;height:14px;border:1px solid var(--border-strong);border-radius:var(--radius-full)}
.selected .mode-indicator{border-color:var(--brand)}.mode-indicator>span{width:6px;height:6px;border-radius:var(--radius-full);background:var(--brand)}
.mode-settings{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;padding-top:14px}
.mode-settings label{display:flex;flex-direction:column;gap:6px;font-size:12px;color:var(--text-secondary)}
.mode-settings .el-input-number,.mode-settings .el-select{width:100%}.mode-settings p,.advanced{grid-column:1/-1}
.mode-settings p,.standard-note{font-size:12px;line-height:1.6;color:var(--text-secondary);margin:0}.standard-note{margin-top:12px}.checkbox-label{justify-content:center}
@media(max-width:850px){.mode-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.mode-settings{grid-template-columns:1fr 1fr}.mode-note{max-width:150px}}
@media(max-width:520px){.continuity-heading{align-items:flex-start;flex-direction:column}.mode-note{max-width:none}.mode-settings{grid-template-columns:1fr}}
@media(prefers-reduced-motion:reduce){.mode-card{transition:none}}
</style>
