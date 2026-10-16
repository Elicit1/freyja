<template>
  <el-dialog v-model="visible" title="批量视频渲染" width="min(1240px, 96vw)" :close-on-click-modal="false" destroy-on-close>
    <div v-loading="loading" class="flex gap-5 min-h-[520px]">
      <aside class="w-64 shrink-0 border-r border-[var(--border-default)] pr-4">
        <div class="flex justify-between mb-3"><span class="font-medium">选择分镜</span><span><el-button link @click="selectAll">全选</el-button><el-button link @click="clearSelection">清空</el-button></span></div>
        <el-input v-model="filter" placeholder="筛选剧集、场景、镜头组" clearable class="mb-3" />
        <el-scrollbar height="470px">
          <el-tree ref="tree" :data="nodes" node-key="key" show-checkbox :props="{ label: 'label', children: 'children' }" :filter-node-method="filterNode" @check="selectionChanged" />
        </el-scrollbar>
      </aside>
      <section class="flex-1 min-w-0 space-y-4">
        <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
          <el-input v-model="name" placeholder="批次名称" maxlength="100" />
          <el-select v-model="ratio" aria-label="统一画面比例"><el-option v-for="r in ratios" :key="r" :value="r" :label="`整个批次画面比例 ${r}`" /></el-select>
          <el-select v-model="size" aria-label="统一渲染尺寸" placeholder="统一渲染尺寸（宽x高）" filterable allow-create default-first-option>
            <el-option v-for="preset in sizePresets" :key="preset" :value="preset" :label="`统一渲染尺寸 ${preset}`" />
          </el-select>
        </div>
        <div class="text-xs text-[var(--text-secondary)]">统一尺寸应用于整个批次的 REF2VA 和 FL2VA。比例用于推荐尺寸；支持自定义宽高，均须为 32 像素的整数倍。</div>
        <el-radio-group v-model="policy"><el-radio value="SKIP">仅缺少当前视频</el-radio><el-radio value="CREATE_NEW_TAKE">全部再生成一个版本</el-radio></el-radio-group>
        <div v-for="mode in modes" :key="mode.key" class="rounded-lg border border-[var(--border-default)] p-3">
          <div class="font-semibold mb-2">{{ mode.label }}</div>
          <div class="grid grid-cols-2 gap-3">
            <el-select v-model="configs[mode.key].providerId" placeholder="选择提供商" @change="loadModels(mode.key)"><el-option v-for="p in providers" :key="String(p.id)" :value="String(p.id)" :label="p.providerName" /></el-select>
            <el-select v-model="configs[mode.key].modelId" placeholder="选择渲染模型" :disabled="!configs[mode.key].providerId"><el-option v-for="m in modelLists[mode.key]" :key="String(m.id)" :value="String(m.id)" :label="m.modelName || m.modelCode" /></el-select>
          </div>
          <div v-if="mode.key === 'FIRST_LAST_FRAME'" class="mt-2 flex flex-wrap gap-x-4">
            <el-checkbox v-model="configs.FIRST_LAST_FRAME.autoUsePreviousVideoTailAsFirstFrame">自动使用上一镜视频尾帧作为首帧</el-checkbox>
            <el-checkbox v-model="configs.FIRST_LAST_FRAME.autoUseNextVideoHeadAsLastFrame">自动使用下一镜视频首帧作为尾帧</el-checkbox>
          </div>
        </div>
        <el-alert type="info" :closable="false" title="先 REF2VA，后 FL2VA；始终逐个渲染。已配置首尾帧优先，缺少可用邻镜视频时不传对应帧。" />
        <el-table :data="preview?.items || []" height="240" empty-text="选择分镜和模型后，点击检查" size="small">
          <el-table-column prop="executionOrder" label="顺序" width="55" /><el-table-column prop="name" label="镜头" min-width="130" />
          <el-table-column label="模式" width="85"><template #default="{ row }">{{ row.generationMode === 'REFERENCE_MODE' ? 'REF2VA' : 'FL2VA' }}</template></el-table-column>
          <el-table-column prop="firstFrameSource" label="首帧来源" min-width="125" /><el-table-column prop="lastFrameSource" label="尾帧来源" min-width="125" />
          <el-table-column prop="size" label="实际尺寸" width="100" /><el-table-column label="检查结果" min-width="150"><template #default="{ row }"><span :class="row.status === 'READY' ? 'text-emerald-600' : 'text-amber-600'">{{ row.reason || '可提交' }}</span></template></el-table-column>
        </el-table>
      </section>
    </div>
    <template #footer>
      <div class="flex items-center justify-between gap-4">
        <div class="text-sm text-[var(--text-secondary)]">已选 {{ selectedIds.length }} 镜<span v-if="preview"> · 可提交 {{ preview.accepted }} · 待补充 {{ preview.blocked }} · 跳过 {{ preview.skipped }} · 输出总时长 {{ preview.duration }} 秒</span></div>
        <div><el-button @click="visible = false">关闭</el-button><el-button :loading="checking" :disabled="!selectedIds.length || submitting" @click="check">检查</el-button><el-button type="primary" :loading="submitting" :disabled="!preview?.accepted" @click="submit">提交可用的 {{ preview?.accepted || 0 }} 镜</el-button></div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElTree } from 'element-plus'
import { useRouter } from 'vue-router'
import { aiProviderApi } from '@/api/ai-provider'
import type { AiModel, AiProviderVO } from '@/types/ai-provider'
import { resolveGenerationSize, validateGenerationSize } from '@/utils/generation-size'
import { videoBatchApi, type BatchModeConfig, type BatchPreview, type BatchSelectionNode, type VideoBatchRequest, type VideoMode } from '@/api/video-render-batch'
const router = useRouter()
const visible = ref(false), loading = ref(false), checking = ref(false), submitting = ref(false)
const dramaId = ref(''), name = ref('批量视频渲染'), ratio = ref('16:9'), size = ref('960x544'), filter = ref('')
const policy = ref<'SKIP' | 'CREATE_NEW_TAKE'>('SKIP')
const ratios = ['16:9', '9:16', '1:1', '4:3', '3:4', '3:2', '2:3']
const sizesByRatio: Record<string, string[]> = {
  '16:9': ['960x544', '1024x576', '1280x736', '1344x768'],
  '9:16': ['544x960', '576x1024', '736x1280', '768x1344'],
  '1:1': ['768x768', '1024x1024'],
  '4:3': ['1024x768', '1152x864'],
  '3:4': ['768x1024', '864x1152'],
  '3:2': ['1152x768', '1536x1024'],
  '2:3': ['768x1152', '1024x1536']
}
const sizePresets = computed(() => sizesByRatio[ratio.value] || [resolveGenerationSize(ratio.value, 'video')])
const modes: { key: VideoMode; label: string; type: string }[] = [{ key: 'REFERENCE_MODE', label: '阶段一 · REF2VA 参考图模式', type: 'TXT2VIDEO_REF' }, { key: 'FIRST_LAST_FRAME', label: '阶段二 · FL2VA 首尾帧模式', type: 'TXT2VIDEO_FIRST_LAST' }]
const configs = reactive<Record<VideoMode, BatchModeConfig>>({ REFERENCE_MODE: {}, FIRST_LAST_FRAME: { autoUsePreviousVideoTailAsFirstFrame: true, autoUseNextVideoHeadAsLastFrame: false } })
const modelLists = reactive<Record<VideoMode, AiModel[]>>({ REFERENCE_MODE: [], FIRST_LAST_FRAME: [] })
const providers = ref<AiProviderVO[]>([]), nodes = ref<BatchSelectionNode[]>([]), selectedIds = ref<string[]>([])
const tree = ref<InstanceType<typeof ElTree>>(), preview = ref<BatchPreview | null>(null)
let pendingRequest: VideoBatchRequest | null = null
let requestId = ''
let revision = 0
function invalidate() { revision++; preview.value = null; pendingRequest = null; requestId = crypto.randomUUID() }
watch([ratio, size, policy, configs, name], invalidate, { deep: true, flush: 'sync' })
watch(ratio, value => { size.value = resolveGenerationSize(value, 'video') }, { flush: 'sync' })
watch(filter, value => tree.value?.filter(value))
function filterNode(value: string, data: Record<string, any>) { return !value || String(data.label).includes(value) }
function selectionChanged() { selectedIds.value = (tree.value?.getCheckedNodes(false, false) || []).filter(n => n.type === 'SHOT').map(n => String(n.id)); invalidate() }
function selectAll() { const keys: string[] = []; const visit = (list: BatchSelectionNode[]) => list.forEach(n => { if (n.type === 'SHOT') keys.push(n.key); visit(n.children) }); visit(nodes.value); tree.value?.setCheckedKeys(keys); selectionChanged() }
function clearSelection() { tree.value?.setCheckedKeys([]); selectionChanged() }
async function loadModels(mode: VideoMode) {
  configs[mode].modelId = undefined; modelLists[mode] = []; const provider = configs[mode].providerId
  if (!provider) return
  try { const list = await aiProviderApi.getModelList(provider, modes.find(m => m.key === mode)!.type); if (configs[mode].providerId === provider) modelLists[mode] = list.filter(m => m.status === 1) }
  catch (e: any) { ElMessage.error(e.message || '加载视频模型失败') }
}
async function open(id: string | number, aspectRatio?: string) {
  visible.value = true; loading.value = true; dramaId.value = String(id); selectedIds.value = []; preview.value = null; nodes.value = []; filter.value = ''
  name.value = '批量视频渲染'; policy.value = 'SKIP'; ratio.value = ratios.includes(aspectRatio || '') ? aspectRatio! : '16:9'; invalidate()
  size.value = resolveGenerationSize(ratio.value, 'video')
  try { const [data, enabled] = await Promise.all([videoBatchApi.tree(dramaId.value), aiProviderApi.getListEnabled()]); nodes.value = data; providers.value = enabled; await nextTick(); clearSelection() }
  catch (e: any) { ElMessage.error(e.message || '加载分镜失败') } finally { loading.value = false }
}
async function check() {
  if (selectedIds.value.length > 200) { ElMessage.warning('每批最多 200 镜，请缩小选择范围'); return }
  const sizeValidation = validateGenerationSize(size.value, 'video')
  if (!sizeValidation.valid || !sizeValidation.normalized) { ElMessage.warning(sizeValidation.message || '请选择有效的统一渲染尺寸'); return }
  size.value = sizeValidation.normalized
  const current = revision; checking.value = true
  const request: VideoBatchRequest = { dramaId: dramaId.value, name: name.value, shotIds: [...selectedIds.value], aspectRatio: ratio.value, size: sizeValidation.normalized, existingVideoPolicy: policy.value, modeConfigs: JSON.parse(JSON.stringify(configs)) }
  try { const result = await videoBatchApi.preview(request); if (current !== revision) return; preview.value = result; pendingRequest = { ...request, shotIds: result.shotIds, expectedPreviewHash: result.previewHash, clientRequestId: requestId } }
  catch (e: any) { ElMessage.error(e.message || '检查失败') } finally { checking.value = false }
}
async function submit() {
  if (!pendingRequest || !preview.value?.accepted) return
  submitting.value = true
  try { const batch = await videoBatchApi.create(pendingRequest); visible.value = false; ElMessage.success('已加入串行渲染队列'); await router.push({ path: '/render/tasks', query: { taskSource: 'VIDEO_RENDER_BATCH', taskId: String(batch.id) } }) }
  catch (e: any) { ElMessage.error(e.message || '提交失败，请检查后重试') } finally { submitting.value = false }
}
defineExpose({ open })
</script>
