<template>
  <div class="filter-editor">
    <div class="catalog-controls"><el-select v-model="category" clearable placeholder="全部处理分类"><el-option v-for="item in categories" :key="item" :label="item" :value="item" /></el-select><el-input v-model="search" clearable placeholder="搜索处理名称或滤镜名称" /></div>
    <el-select v-model="addType" placeholder="选择滤镜，添加处理步骤" @change="add" class="add-filter" :disabled="modelValue.length >= 12">
      <el-option-group v-for="group in groups" :key="group.label" :label="group.label"><el-option v-for="filter in group.items" :key="filter.type" :label="filter.label + (filter.available ? '' : '（服务器不可用）')" :value="filter.type" :disabled="!filter.available"><span>{{ filter.label }}</span><small class="filter-code">{{ filter.type }}</small></el-option></el-option-group>
    </el-select>
    <p class="hint">{{ filters.length }} 种处理 · {{ parameterCount }} 个参数 · 每个特效最多12步。可重复添加滤镜，按顺序执行。</p>
    <el-empty v-if="!modelValue.length" description="添加需要的处理，再调整参数组合成特效" :image-size="45" />
    <div v-for="(step, index) in modelValue" :key="index" class="filter-step" :class="{ bypassed: step.enabled === false }" :data-filter="step.type">
      <div class="step-heading"><strong>{{ index + 1 }}. {{ definition(step.type)?.label || step.type }}</strong><el-switch :model-value="step.enabled !== false" active-text="启用" inactive-text="跳过" @update:model-value="toggle(index, !!$event)" /><div class="step-actions"><el-button size="small" :disabled="modelValue.length >= 12" @click="duplicate(index)">复制</el-button><el-button size="small" :disabled="index === 0" @click="move(index, -1)">上移</el-button><el-button size="small" :disabled="index === modelValue.length - 1" @click="move(index, 1)">下移</el-button><el-button size="small" type="danger" text @click="remove(index)">删除</el-button></div></div>
      <p class="step-description">{{ definition(step.type)?.description }}</p>
      <el-alert v-if="definition(step.type) && !definition(step.type)?.available" :title="'服务器缺少滤镜：' + (definition(step.type)?.missingFilters || []).join('、')" type="warning" :closable="false" />
      <p class="filter-identity">{{ definition(step.type)?.ffmpegFilter }}<el-button text size="small" @click="reset(index)">重置参数</el-button></p>
      <FilterParameterEditor v-for="parameter in parameters(step.type, false)" :key="parameter.key" :definition="parameter" :model-value="step.params[parameter.key]" @update:model-value="setParameter(index, parameter.key, $event)" />
      <el-collapse v-if="parameters(step.type, true).length"><el-collapse-item title="更多参数" name="advanced"><FilterParameterEditor v-for="parameter in parameters(step.type, true)" :key="parameter.key" :definition="parameter" :model-value="step.params[parameter.key]" @update:model-value="setParameter(index, parameter.key, $event)" /></el-collapse-item></el-collapse>
    </div>
  </div>
</template>
<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { Step, Filter, ParameterValue } from '@/api/dubbing'
import FilterParameterEditor from './FilterParameter.vue'
const props = defineProps<{ modelValue: Step[]; filters: Filter[] }>()
const emit = defineEmits<{ 'update:modelValue': [steps: Step[]] }>()
const addType = ref(''), category = ref(''), search = ref('')
const definition = (type: string) => props.filters.find(f => f.type === type)
const parameters = (type: string, advanced: boolean) => (definition(type)?.parameters || []).filter(p => !!p.advanced === advanced)
const categories = computed(() => [...new Set(props.filters.map(f => f.category))])
const parameterCount = computed(() => props.filters.reduce((total, f) => total + f.parameters.length, 0))
const groups = computed(() => categories.value.filter(item => !category.value || category.value === item).map(label => ({ label, items: props.filters.filter(f => f.category === label && (!search.value || `${f.label} ${f.type} ${f.ffmpegFilter}`.toLowerCase().includes(search.value.trim().toLowerCase()))) })).filter(group => group.items.length))
function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) }
function copy() { return clone(props.modelValue) }
function defaults(type: string) { return Object.fromEntries((definition(type)?.parameters || []).map(p => [p.key, clone(p.initial)])) }
function add(type: string) { if (props.modelValue.length >= 12) return; const filter = definition(type); if (filter?.available) emit('update:modelValue', [...copy(), { type, params: defaults(type), enabled: true }]); addType.value = '' }
function move(index: number, offset: number) { const steps = copy(); const [item] = steps.splice(index, 1); if (item) steps.splice(index + offset, 0, item); emit('update:modelValue', steps) }
function remove(index: number) { emit('update:modelValue', copy().filter((_, i) => i !== index)) }
function duplicate(index: number) { if (props.modelValue.length >= 12) return; const steps = copy(); const original = steps[index]; if (original) steps.splice(index + 1, 0, clone(original)); emit('update:modelValue', steps) }
function toggle(index: number, enabled: boolean) { const steps = copy(); if (steps[index]) steps[index]!.enabled = enabled; emit('update:modelValue', steps) }
function reset(index: number) { const steps = copy(); const item = steps[index]; if (!item || !definition(item.type)) { ElMessage.warning('处理定义不可用'); return } item.params = defaults(item.type); emit('update:modelValue', steps) }
function setParameter(index: number, key: string, value: ParameterValue) { const steps = copy(); if (steps[index]) steps[index]!.params[key] = value; emit('update:modelValue', steps) }
</script>
<style scoped>
.filter-editor{width:100%;min-width:0}.catalog-controls{display:grid;grid-template-columns:180px 1fr;gap:12px;margin-bottom:10px}.add-filter{width:100%}.filter-code{float:right;color:var(--el-text-color-secondary);font-size:11px;margin-left:20px}.filter-step{border:1px solid var(--el-border-color-light);border-radius:12px;padding:18px;margin:14px 0}.filter-step.bypassed{background:var(--el-fill-color-light);border-style:dashed}.step-heading,.step-actions{display:flex;align-items:center;gap:8px}.step-heading{flex-wrap:wrap;gap:14px}.step-heading strong{font-size:14px}.step-actions{margin-left:auto;flex-wrap:wrap}.step-description,.hint{font-size:12px;color:var(--el-text-color-secondary);line-height:1.7;margin:10px 0}.filter-identity{display:flex;align-items:center;justify-content:space-between;font-size:11px;color:var(--el-text-color-secondary);font-family:monospace}.filter-step .el-collapse{border-bottom:0;margin-top:6px}@media(max-width:650px){.catalog-controls{grid-template-columns:1fr}.step-actions{margin-left:0}.filter-step{padding:12px}}
</style>
