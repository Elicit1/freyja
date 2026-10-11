<template>
  <Teleport to="body">
  <section v-if="store.visible" class="agent-chat-window" role="dialog" aria-label="与 Agent 聊天" aria-modal="false" @keydown.esc="store.visible = false">
    <header class="chat-heading"><strong>与 Agent 聊天</strong><el-button text aria-label="关闭 Agent 聊天" @click="store.visible = false">×</el-button></header>
    <div class="chat-layout">
      <div class="flex gap-2 items-center flex-wrap mb-3">
        <el-select v-model="sessionId" placeholder="选择历史会话" class="flex-1 min-w-40" :disabled="sending" @change="select">
          <el-option v-for="session in store.sessions" :key="session.id" :label="session.title" :value="session.id" />
        </el-select>
        <el-button @click="newSessionVisible = true" :disabled="sending || !store.profiles.length">新会话</el-button>
        <el-button type="primary" plain @click="productionVisible = true" :disabled="sending">小说制作准备</el-button>
        <el-button text @click="configure">配置</el-button>
      </div>
      <el-alert v-if="!store.profiles.length" title="请先在 Agent 配置页选择模型并启用助手。" type="info" :closable="false" class="mb-3" />
      <div v-if="store.selectedSession" class="text-xs text-[var(--text-secondary)] mb-3">
        {{ store.currentProfile?.displayName || 'Agent' }} · {{ store.selectedSession.dramaId ? '项目会话' : '通用会话' }}
        <span v-if="store.activeRun"> · {{ agentStatusLabels[store.activeRun.status] }}</span>
      </div>
      <div ref="scrollArea" v-loading="store.loading" class="chat-history">
        <el-empty v-if="!store.messages.length" description="告诉 Agent 你希望完成什么" :image-size="70" />
        <AgentMessageList :messages="store.messages" />
        <AgentRunCard v-for="run in shownRuns" :key="run.id" :run="run" class="mt-3" @refreshed="store.refreshRun(run.id)" />
      </div>
      <div class="chat-input">
        <el-input v-model="input" type="textarea" :rows="3" :maxlength="100000"
          :placeholder="store.activeRun?.status === 'WAITING_USER' ? '回答 Agent 的问题…' : store.activeRun ? '补充本次任务的要求…' : '输入目标，Enter 发送，Shift+Enter 换行…'"
          @keydown="keyDown" />
        <div class="flex justify-between items-center mt-3">
          <el-button v-if="store.activeRun && isActiveRun(store.activeRun.status)" size="small" type="danger" plain @click="cancel">取消任务</el-button>
          <span v-else class="text-xs text-[var(--text-secondary)]">关闭聊天后，后台任务继续运行</span>
          <el-button type="primary" :loading="sending" :disabled="!input.trim() || (!store.profiles.length && !store.activeRun)" @click="send">
            {{ store.activeRun ? store.activeRun.status === 'WAITING_USER' ? '回答并继续' : '补充要求' : '发送' }}
          </el-button>
        </div>
      </div>
    </div>
  </section>
  </Teleport>
  <ProductionStartDialog v-model="productionVisible" />
  <el-dialog v-model="newSessionVisible" title="新建 Agent 会话" width="480px" append-to-body>
    <el-form label-position="top">
      <el-form-item label="Agent"><el-select v-model="store.selectedAgentCode" class="w-full">
        <el-option v-for="profile in store.profiles" :key="profile.agentCode" :label="profile.displayName" :value="profile.agentCode" />
      </el-select></el-form-item>
      <el-form-item label="绑定项目（可选）"><el-select v-model="dramaId" clearable filterable class="w-full" placeholder="通用会话">
        <el-option v-for="project in projects" :key="String(project.id)" :label="project.title" :value="String(project.id)" />
      </el-select></el-form-item>
    </el-form>
    <template #footer><el-button @click="newSessionVisible = false">取消</el-button><el-button type="primary" @click="create">创建</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { dramaApi } from '@/api/drama'
import { useAgentStore } from '@/store/agent'
import { agentStatusLabels, isActiveRun } from '@/types/agent'
import AgentMessageList from './AgentMessageList.vue'
import AgentRunCard from './AgentRunCard.vue'
import ProductionStartDialog from './ProductionStartDialog.vue'
const store = useAgentStore()
const router = useRouter()
const input = ref('')
const sending = ref(false)
const newSessionVisible = ref(false)
const productionVisible = ref(false)
const dramaId = ref<string>()
const projects = ref<{ id: string | number; title: string }[]>([])
const scrollArea = ref<HTMLElement>()
const sessionId = computed({ get: () => store.selectedSession?.id, set: () => {} })
const shownRuns = computed(() => Object.values(store.runs).filter(r => r.sessionId === store.selectedSession?.id).sort((a, b) => (a.createTime || '').localeCompare(b.createTime || '')))
let pendingRequest: { content: string; sessionId?: string; id: string } | undefined
async function send() {
  const content = input.value.trim()
  if (!content || sending.value) return
  sending.value = true
  try {
    if (!store.selectedSession) await store.createSession()
    if (!pendingRequest || pendingRequest.content !== content || pendingRequest.sessionId !== store.selectedSession?.id)
      pendingRequest = { content, sessionId: store.selectedSession?.id, id: crypto.randomUUID() }
    await store.send(content, pendingRequest.id); input.value = ''; pendingRequest = undefined
  }
  finally { sending.value = false }
}
function keyDown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); void send() }
}
async function select(id: string) { await store.selectSession(id) }
async function create() { await store.createSession(dramaId.value); newSessionVisible.value = false }
async function cancel() { await store.cancel(); ElMessage.success('已停止后续操作') }
function configure() { store.visible = false; void router.push('/system/agent') }
watch(() => store.messages.length, async () => { await nextTick(); scrollArea.value?.scrollTo({ top: scrollArea.value.scrollHeight, behavior: 'smooth' }) })
watch(() => store.focusRunId, async id => { await nextTick(); if (id) document.getElementById(`agent-run-${id}`)?.scrollIntoView({ block: 'center' }) })
watch(newSessionVisible, async visible => { if (visible) projects.value = await dramaApi.getOptions() })
</script>
<style scoped>
.agent-chat-window { position: fixed; z-index: 1990; right: 340px; bottom: 28px; width: min(600px, calc(100vw - 370px)); height: min(740px, calc(100dvh - 100px)); display: flex; flex-direction: column; background: var(--surface); border: 1px solid var(--border-default); border-radius: 18px; box-shadow: 0 16px 60px rgba(0,0,0,.18); padding: 16px 20px; }
.chat-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.chat-layout { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.chat-history { flex: 1; min-height: 0; overflow-y: auto; padding: 2px 4px 18px; }
.chat-input { padding-top: 14px; border-top: 1px solid var(--border-default); }
@media (max-width: 900px) { .agent-chat-window { right: 16px; bottom: 310px; width: calc(100vw - 32px); height: min(620px, calc(100dvh - 330px)); padding: 12px; } }
</style>
