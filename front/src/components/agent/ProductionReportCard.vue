<template>
  <div v-if="report" class="production-report mt-3">
    <strong>{{ report.dramaTitle }} · 制作准备</strong>
    <p class="mt-2">已规划 {{ report.plannedCount }} / {{ report.shotCount }} 镜 · 交付时 {{ report.readyCount }} 镜就绪 · {{ report.waitingImageCount }} 镜待补图</p>
    <div class="flex flex-wrap gap-2 my-3">
      <el-button size="small" @click="workbench()">打开分镜工作台</el-button>
      <el-button size="small" :loading="refreshing" @click="refresh">刷新准备状态</el-button>
      <el-button v-if="needPrompts.length" size="small" type="primary" :disabled="!!store.activeRun" @click="preparePrompts">补齐后更新提示词</el-button>
    </div>
    <p v-if="live.length" class="text-xs mb-2">当前 {{ live.filter(p => p.preparationStatus === 'READY').length }} 镜就绪，{{ live.filter(p => p.preparationStatus === 'WAITING_ASSET_IMAGES').length }} 镜待补图；原交付报告保留。</p>
    <details open><summary>分镜模式与状态</summary>
      <div v-for="s in report.shots" :key="s.shotId" class="result-row">
        <div class="flex justify-between gap-2"><strong>{{ s.shotName || `镜 ${s.shotNo}` }}</strong><el-tag size="small" :type="status(s) === 'READY' ? 'success' : 'warning'">{{ labels[status(s)] || status(s) }}</el-tag></div>
        <p>{{ s.generationMode === 'REFERENCE_MODE' ? '参考图模式' : '首尾帧模式' }} · {{ s.decisionReason }}</p>
        <el-button text size="small" @click="workbench(s.shotId)">查看分镜</el-button><el-button text size="small" @click="openPlan(s.shotId)">查看 / 锁定规划</el-button>
      </div>
    </details>
    <details open class="mt-3"><summary>需要生成或上传图片（{{ report.missingMaterials.length }} 项）</summary>
      <div v-for="m in report.missingMaterials" :key="m.materialKey" class="result-row">
        <div class="flex justify-between gap-2"><strong>{{ m.name }}</strong><el-tag size="small" type="warning">{{ m.status === 'STALE' ? '设定图需更新' : '待补图' }}</el-tag></div>
        <p>{{ m.description }}</p><p class="text-xs">{{ m.purposes.join('；') }} · 影响 {{ m.shotIds.length }} 镜</p>
        <details v-if="m.prompt"><summary>图片制作提示词</summary><p class="whitespace-pre-wrap">{{ m.prompt }}</p></details>
        <el-button text size="small" @click="asset(m)">打开资产并补图</el-button>
      </div>
    </details>
    <details class="mt-3"><summary>资产清单（{{ report.assets.filter(a => a.created).length }} 项新建）</summary>
      <p v-for="a in report.assets" :key="`${a.type}:${a.id}`" class="text-xs mt-1">{{ a.name }} · {{ a.created ? '新建' : '复用' }} · {{ a.imageStatus === 'SYNCED' ? '已有图片' : '待补图' }}</p>
    </details>
    <el-dialog v-model="planVisible" title="分镜制作规划" width="640px" append-to-body>
      <template v-if="selected?.plan">
        <p>{{ selected.plan.decisionReason }}</p>
        <details class="my-3"><summary>完整画面与素材方案</summary><pre class="plan-json">{{ JSON.stringify(selected.plan, null, 2) }}</pre></details>
        <p class="mb-2">锁定的字段将保留当前方案，Agent 后续修改时会遵守。</p>
        <el-checkbox-group v-model="locked"><el-checkbox v-for="f in lockFields" :key="f.value" :value="f.value">{{ f.label }}</el-checkbox></el-checkbox-group>
      </template>
      <template #footer><el-button @click="planVisible = false">关闭</el-button><el-button type="primary" :loading="locking" @click="saveLocks">保存锁定</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { agentApi } from '@/api/agent'
import { useAgentStore } from '@/store/agent'
import type { AgentRun, ProductionReport, ProductionMaterial, ProductionShot, ProductionPlanView } from '@/types/agent'
const props = defineProps<{ run: AgentRun }>()
const report = ref<ProductionReport | null>(), live = ref<ProductionPlanView[]>([])
const store = useAgentStore(), router = useRouter()
const refreshing = ref(false), planVisible = ref(false), locking = ref(false)
const selected = ref<ProductionPlanView>(), locked = ref<string[]>([])
const labels: Record<string, string> = { PENDING:'待规划', WAITING_ASSET_IMAGES:'待补图', NEEDS_REFRESH:'需更新', READY:'就绪', ERROR:'需要修复' }
const lockFields = [{value:'generationMode',label:'生成模式'}, {value:'characterBindings',label:'人物造型'}, {value:'visualAnchor',label:'主视觉基准'}, {value:'propBindings',label:'道具'}, {value:'referenceSlots',label:'参考素材'}, {value:'firstFrameRequirement',label:'首帧方案'}, {value:'lastFrameRequirement',label:'尾帧方案'}, {value:'prompts',label:'提示词'}]
const needPrompts = computed(() => live.value.filter(s => s.preparationStatus === 'NEEDS_REFRESH').map(s => s.shotId))
watch(() => [props.run.id, props.run.status], async () => { if (props.run.agentCode === 'production-preparation') report.value = await agentApi.productionReport(props.run.id) }, { immediate: true })
function status(s: ProductionShot) { return live.value.find(p => p.shotId === s.shotId)?.preparationStatus || s.status }
function workbench(shotId?: string) { store.visible = false; void router.push({ path: '/drama', query: { dramaId: report.value!.dramaId, resumeShotId: shotId } }) }
function asset(m: ProductionMaterial) {
  const type = m.sourceType === 'CHARACTER_LOOK' ? 'character' : m.sourceType.toLowerCase()
  const c = report.value?.assets.find(a => a.type === 'CHARACTER_LOOK' && a.id === m.sourceId)
  const id = type === 'character' ? m.characterId || c?.characterId : m.sourceId
  store.visible = false; void router.push({ path: '/assets', query: { assetType: type, assetId: id, dramaId: report.value!.dramaId } })
}
async function refresh() {
  if (!report.value || refreshing.value) return
  refreshing.value = true
  try {
    const result: ProductionPlanView[] = []
    for (let i = 0; i < report.value.shots.length; i += 100) result.push(...await agentApi.refreshProduction(report.value.dramaId, report.value.shots.slice(i, i + 100).map(s => s.shotId)))
    live.value = result; ElMessage.success('已检查最新素材；素材齐全后可继续更新提示词')
  } finally { refreshing.value = false }
}
async function preparePrompts() {
  await store.selectSession(props.run.sessionId)
  await store.send(`只处理这些分镜的当前制作方案并生成最终提示词，保持已有模式和关联。先读取最新指纹与素材状态，完成后保存局部交付报告：${needPrompts.value.join('、')}`, crypto.randomUUID())
}
async function openPlan(id: string) { selected.value = await agentApi.productionPlan(id); locked.value = [...selected.value.lockedFields]; planVisible.value = true }
async function saveLocks() {
  if (!selected.value) return
  locking.value = true
  try { selected.value = await agentApi.lockProduction(selected.value.shotId, selected.value.expectedRevision, locked.value); ElMessage.success('锁定已保存'); planVisible.value = false }
  finally { locking.value = false }
}
</script>
<style scoped>
.production-report { border-top: 1px solid var(--border-default); padding-top: 14px; }
summary { cursor: pointer; font-weight: 500; }
.result-row { border: 1px solid var(--border-default); border-radius: 8px; padding: 10px; margin-top: 8px; }
p { line-height: 1.65; overflow-wrap: anywhere; }
.plan-json { max-height: 300px; overflow: auto; font-size: 12px; white-space: pre-wrap; }
</style>
