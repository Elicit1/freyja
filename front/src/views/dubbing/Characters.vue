<template>
  <div class="dubbing-center" ref="root">
    <header class="page-header">
      <div><p class="eyebrow">DUBBING STUDIO</p><h1>角色配音</h1><p class="subtitle">一个角色，一份基础声线。为每次表演保留原声，自由调整特效。</p></div>
      <el-button @click="router.push('/dubbing/effects')">管理声音特效</el-button>
    </header>
    <el-form inline class="search-bar" @submit.prevent="search">
      <el-form-item label="人物名称"><el-input v-model="name" clearable placeholder="输入人物名称" @keyup.enter="search" /></el-form-item>
      <el-form-item label="短剧名称"><el-input v-model="dramaName" clearable placeholder="输入短剧名称" @keyup.enter="search" /></el-form-item>
      <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="resetSearch">重置</el-button></el-form-item>
    </el-form>
    <div class="studio-columns">
      <aside class="character-panel" v-loading="charactersLoading">
        <div class="panel-heading"><strong>角色</strong><span>{{ characterTotal }} 位 · 短剧创建时间倒序</span></div>
        <el-empty v-if="!characters.length && !charactersLoading" description="没有匹配的角色" :image-size="65" />
        <button v-for="character in characters" :key="character.id" class="character-row" :class="{ selected: selected?.id === character.id }" @click="selectCharacter(character)">
          <span class="avatar">{{ character.name?.slice(0, 1) || '角' }}</span>
          <span class="character-info"><strong>{{ character.name }}</strong><small>{{ character.drama_title || '公共角色库' }}</small><small :class="character.has_base ? 'ready-label' : ''">{{ character.has_base ? '已建立基础声音' : '待创建基础声音' }}</small></span>
        </button>
        <el-pagination v-model:current-page="characterPage" :page-size="10" :total="characterTotal" layout="prev, pager, next" :pager-count="3" small @current-change="loadCharacters()" />
      </aside>
      <main class="audio-panel" v-loading="audioLoading">
        <el-empty v-if="!selected" description="从左侧选择一位角色，开始创建配音" />
        <template v-else>
          <div class="panel-heading character-heading"><div><h2>{{ selected.name }}</h2><span>{{ selected.drama_title || '公共角色库' }}</span></div><div class="header-actions">
            <el-button :disabled="saving" @click="openUpload">上传基础声音</el-button>
            <el-button :disabled="saving" @click="openCreate('DESIGN')">{{ base ? '重新设计基础声音' : '声音设计' }}</el-button>
            <el-button type="primary" :disabled="!base || base.audio.status !== 'READY'" @click="openCreate('CLONE')">声音克隆</el-button>
          </div></div>
          <section class="base-card">
            <div class="card-title"><el-tag effect="dark">基础参考声音</el-tag><span>无特效 · 全部克隆的音色来源</span><el-tag v-if="base" :type="statusType(base.audio.status)">{{ statusLabel(base.audio.status) }}</el-tag></div>
            <template v-if="base">
              <h3>{{ base.audio.name }}</h3><p class="script-text">{{ base.audio.text }}</p>
              <audio v-if="base.audio.rawAudioUrl" :src="base.audio.rawAudioUrl" controls preload="none" @play="onlyPlay" />
              <p v-if="base.audio.errorMessage" class="error-text">{{ base.audio.errorMessage }}</p>
              <div class="card-footer"><span>{{ duration(base.audio.rawDuration) }}<span v-if="base.references"> · {{ base.references }} 个分镜引用</span></span><div><el-button v-if="failed(base.audio.status)" size="small" @click="retry(base)">重试</el-button><el-button size="small" text type="danger" @click="remove(base)">删除基础声音</el-button></div></div>
            </template>
            <p v-else class="empty-base">尚未建立基础声音。先通过声音设计或上传无特效干声，再生成角色的克隆配音。</p>
          </section>
          <div v-for="candidate in replacements" :key="candidate.audio.id" class="replacement-status">
            <el-tag :type="statusType(candidate.audio.status)">基础声音重新设计 · {{ statusLabel(candidate.audio.status) }}</el-tag>
            <span v-if="candidate.audio.errorMessage" class="error-text">{{ candidate.audio.errorMessage }}</span>
            <el-button v-if="failed(candidate.audio.status)" size="small" text @click="retry(candidate)">重试</el-button>
            <el-button size="small" type="danger" text @click="remove(candidate)">取消 / 删除此任务</el-button>
          </div>
          <div class="audio-filters"><strong>克隆配音 <small>{{ audioTotal }}</small></strong>
            <el-select v-model="emotionFilter" multiple clearable placeholder="按情绪标签筛选" @change="filterAudios"><el-option v-for="emotion in emotions" :key="emotion.dictValue" :label="emotion.dictLabel" :value="emotion.dictValue" /></el-select>
            <el-select v-model="effectFilter" multiple clearable filterable placeholder="按特效标签筛选" @change="filterAudios"><el-option v-for="effect in allEffects" :key="String(effect.id)" :label="effect.name + (effect.enabled ? '' : '（已停用）')" :value="String(effect.id)" /></el-select>
            <el-button text @click="loadAudios()">刷新</el-button>
          </div>
          <el-empty v-if="!audios.length && !audioLoading" description="暂无匹配的克隆配音" :image-size="75" />
          <div class="audio-grid">
            <article v-for="item in audios" :key="item.audio.id" class="audio-card">
              <div class="card-title"><h3>{{ item.audio.name }}</h3><el-tag size="small" :type="statusType(item.audio.status)">{{ statusLabel(item.audio.status) }}</el-tag></div>
              <div class="tags"><el-tag v-if="item.audio.emotion" size="small" type="success">{{ item.audio.emotion }}</el-tag><el-tag v-for="effect in item.effects" :key="effect.effectId" size="small" type="warning">{{ effect.name }}</el-tag><span v-if="!item.effects.length" class="muted">无特效 · 克隆原声</span></div>
              <p class="script-text">{{ item.audio.text }}</p>
              <p v-if="item.effects.length && item.audio.status !== 'READY'" class="muted">{{ item.audio.status === 'PROCESSING' ? '特效处理中，当前可试听原始克隆音频' : '特效尚未就绪，当前为原始克隆音频' }}</p>
              <audio v-if="item.playbackUrl" :src="item.playbackUrl" controls preload="none" @play="onlyPlay" />
              <details v-if="item.effects.length && item.audio.processedAudioUrl"><summary>试听保留的克隆原声</summary><audio :src="item.audio.rawAudioUrl" controls preload="none" @play="onlyPlay" /></details>
              <p v-if="item.audio.errorMessage" class="error-text">{{ item.audio.errorMessage }}</p>
              <div class="audio-meta">{{ duration(item.duration) }} · {{ date(item.audio.createTime) }}<span v-if="item.references"> · {{ item.references }} 个分镜引用</span></div>
              <div class="card-actions"><el-button size="small" :disabled="!item.audio.rawAudioUrl" @click="openEffects(item)">编辑特效</el-button><el-button v-if="item.effects.length" size="small" text @click="clearEffects(item)">清空特效</el-button><el-button v-if="failed(item.audio.status)" size="small" type="primary" text @click="retry(item)">重试</el-button>
                <el-dropdown trigger="click" @command="(command: string) => cardAction(command, item)"><el-button size="small" text>更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="rename">修改名称</el-dropdown-item><el-dropdown-item command="regenerate">修改台词 / 情绪并生成新配音</el-dropdown-item><el-dropdown-item command="delete" divided>删除</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
              </div>
            </article>
          </div>
          <el-pagination v-model:current-page="audioPage" :page-size="9" :total="audioTotal" layout="total, prev, pager, next" @current-change="loadAudios()" class="audio-pagination" />
        </template>
      </main>
    </div>

    <el-dialog v-model="createVisible" :title="uploadMode ? '上传无特效基础声音' : form.method === 'DESIGN' ? (base ? '重新设计基础声音' : '声音设计') : '创建克隆配音'" width="min(760px, 94vw)" destroy-on-close :close-on-click-modal="false">
      <el-alert v-if="uploadMode || form.method === 'DESIGN'" :title="base ? '替换成功后会删除原基础声音及全部关联克隆。生成失败会保留原声音。' : '基础声音必须为无特效干声，用于后续所有声音克隆。'" :type="base ? 'warning' : 'info'" :closable="false" show-icon class="dialog-alert" />
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="配音名称" required><el-input v-model="form.name" maxlength="128" show-word-limit /></el-form-item>
        <el-form-item v-if="!uploadMode" label="提供商 / 模型" required><el-select v-model="modelKey" class="!w-full" placeholder="选择已启用的 MiMo 模型"><el-option v-for="model in availableModels" :key="model.providerId + '|' + model.modelCode" :value="model.providerId + '|' + model.modelCode" :label="model.providerName + ' / ' + model.modelName" /></el-select><p v-if="!availableModels.length" class="muted">请先在 AI 提供商中配置并启用对应的 MiMo TTS 模型。</p></el-form-item>
        <el-form-item v-if="!uploadMode && form.method === 'DESIGN'" label="音色描述" required><el-input v-model="form.voiceDesc" type="textarea" :rows="3" maxlength="512" show-word-limit placeholder="描述年龄、声线和说话特点，请勿要求混响、无线电等特效" /></el-form-item>
        <el-form-item :label="uploadMode ? '音频实际台词' : '配音台词'" required><el-input v-model="form.text" type="textarea" :rows="4" maxlength="4000" show-word-limit :placeholder="form.method === 'DESIGN' ? '基础参考建议5至10秒，最长30秒' : '输入本次表演的台词'" /></el-form-item>
        <el-form-item v-if="uploadMode" label="选择音频文件" required><input type="file" accept="audio/*" @change="chooseUpload" /><p class="muted">最大7 MiB，最长30秒；请上传清晰且不带特效的原始人声。</p></el-form-item>
        <template v-if="!uploadMode && form.method === 'CLONE'"><el-form-item label="情绪"><el-select v-model="form.emotion" clearable placeholder="选择表演情绪"><el-option v-for="emotion in emotions" :key="emotion.dictValue" :value="emotion.dictValue" :label="emotion.dictLabel" /></el-select></el-form-item>
          <el-form-item label="声音特效（可选）"><EffectSelection v-model="form.effects" :effects="enabledEffects" :filters="filters" class="!w-full" /></el-form-item>
          <el-alert title="先生成并保留 TTS 克隆原声，再添加特效。后续编辑或清空特效不会调用 TTS。" type="info" :closable="false" />
        </template>
      </el-form>
      <template #footer><el-button @click="createVisible = false" :disabled="saving">取消</el-button><el-button type="primary" :loading="saving" @click="submitCreate">{{ uploadMode ? '上传基础声音' : '开始生成' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="effectsVisible" title="编辑声音特效" width="min(800px, 94vw)" destroy-on-close :close-on-click-modal="false">
      <el-alert title="始终从保留的克隆原声重新处理，不调用 TTS。清空全部特效后直接播放克隆原声。" type="info" :closable="false" class="dialog-alert" />
      <EffectSelection v-model="editingEffects" :effects="enabledEffects" :filters="filters" :names="editingNames" />
      <div class="preview-grid"><div><p>克隆原声</p><audio v-if="editing?.audio.rawAudioUrl" :src="editing.audio.rawAudioUrl" controls preload="none" @play="onlyPlay" /></div><div><p>当前参数预览</p><audio v-if="effectPreview" :src="effectPreview" controls @play="onlyPlay" /><el-button v-else :loading="previewing" :disabled="!editingEffects.length" @click="previewEffects">试听特效</el-button></div></div>
      <template #footer><el-button @click="effectsVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEffects">{{ editingEffects.length ? '保存并处理' : '清空特效，使用原声' }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dubbingApi, type Character, type AudioView, type Effect, type Filter, type Model, type CreateAudio, type Selection } from '@/api/dubbing'
import { dictCommonApi } from '@/api/dict'
import type { SysDictData } from '@/types/dict'
import EffectSelection from './components/EffectSelection.vue'
const route = useRoute(), router = useRouter(), root = ref<HTMLElement>()
const name = ref(''), dramaName = ref(''), characters = ref<Character[]>([]), selected = ref<Character>(), characterPage = ref(1), characterTotal = ref(0), charactersLoading = ref(false)
const replacements = ref<AudioView[]>([])
const base = ref<AudioView | null>(null), audios = ref<AudioView[]>([]), audioPage = ref(1), audioTotal = ref(0), audioLoading = ref(false), emotionFilter = ref<string[]>([]), effectFilter = ref<string[]>([])
const emotions = ref<SysDictData[]>([]), allEffects = ref<Effect[]>([]), filters = ref<Filter[]>([]), models = ref<Model[]>([])
const enabledEffects = computed(() => allEffects.value.filter(e => e.enabled === 1))
const saving = ref(false), createVisible = ref(false), uploadMode = ref(false), uploadFile = ref<File>(), modelKey = ref('')
const form = reactive<CreateAudio>({ name: '', text: '', providerId: '', modelCode: '', method: 'DESIGN', voiceDesc: '', effects: [] })
const availableModels = computed(() => models.value.filter(m => m.modelCode === (form.method === 'DESIGN' ? 'mimo-v2.5-tts-voicedesign' : 'mimo-v2.5-tts-voiceclone')))
const effectsVisible = ref(false), editing = ref<AudioView>(), editingEffects = ref<Selection[]>([]), editingNames = ref<Record<string, string>>({}), effectPreview = ref(''), previewing = ref(false)
let characterRequest = 0, audioRequest = 0, poll: ReturnType<typeof setTimeout> | undefined, previewVersion = 0, disposed = false
const statuses: Record<string, string> = { GENERATING: '声音生成中', PROCESSING: '特效处理中', READY: '已就绪', FAILED: '生成失败', EFFECT_FAILED: '特效失败', CANCELLED: '已取消' }
const statusLabel = (s: string) => statuses[s] || s
const statusType = (s: string) => s === 'READY' ? 'success' as const : failed(s) ? 'danger' as const : 'warning' as const
const failed = (s: string) => ['FAILED', 'EFFECT_FAILED', 'CANCELLED'].includes(s)
const duration = (value?: number) => value == null ? '时长待测' : `${value.toFixed(2)} 秒`
const date = (value?: string) => value?.replace('T', ' ').slice(0, 16) || ''
function stopAudio() { root.value?.querySelectorAll('audio').forEach(a => a.pause()); document.querySelectorAll<HTMLAudioElement>('.el-dialog audio').forEach(a => a.pause()) }
function onlyPlay(event: Event) { document.querySelectorAll<HTMLAudioElement>('audio').forEach(a => { if (a !== event.target) a.pause() }) }
async function loadCharacters(locateId?: string) {
  const sequence = ++characterRequest; charactersLoading.value = true
  try {
    const result = await dubbingApi.characters({ current: characterPage.value, size: 10, name: name.value || undefined, dramaName: dramaName.value || undefined, locateId })
    if (sequence !== characterRequest || disposed) return
    characters.value = result.page.records; characterTotal.value = Number(result.page.total); characterPage.value = Number(result.page.current)
    if (locateId) { const character = characters.value.find(c => c.id === locateId); if (character) await selectCharacter(character) }
    else if (!selected.value || !characters.value.some(c => c.id === selected.value?.id)) {
      const first = characters.value[0]
      if (first) await selectCharacter(first)
      else { selected.value = undefined; base.value = null; audios.value = []; audioTotal.value = 0; stopAudio(); ++audioRequest; await router.replace({ query: {} }) }
    } else selected.value = characters.value.find(c => c.id === selected.value?.id)
  } finally { if (sequence === characterRequest) charactersLoading.value = false }
}
async function selectCharacter(character: Character) {
  if (selected.value?.id === character.id) return
  stopAudio(); selected.value = character; base.value = null; replacements.value = []; audios.value = []; audioPage.value = 1; emotionFilter.value = []; effectFilter.value = []
  createVisible.value = false; effectsVisible.value = false
  await router.replace({ query: { characterId: character.id } }); await loadAudios()
}
async function loadAudios(quiet = false) {
  const characterId = selected.value?.id; if (!characterId) return
  const sequence = ++audioRequest; if (!quiet) audioLoading.value = true
  try {
    const [reference, page, candidates] = await Promise.all([dubbingApi.base(characterId), dubbingApi.audios(characterId, { current: audioPage.value, size: 9, emotions: emotionFilter.value, effectIds: effectFilter.value }), dubbingApi.replacements(characterId)])
    if (sequence !== audioRequest || characterId !== selected.value?.id || disposed) return
    base.value = reference; replacements.value = candidates; audios.value = page.records; audioTotal.value = Number(page.total)
    const character = characters.value.find(c => c.id === characterId); if (character) character.has_base = reference ? 1 : 0
    if (!page.records.length && audioPage.value > 1 && page.total > 0) { audioPage.value = Math.ceil(page.total / 9); await loadAudios(quiet) }
  } finally { if (sequence === audioRequest) audioLoading.value = false }
}
function search() { characterPage.value = 1; void loadCharacters() }
function resetSearch() { name.value = ''; dramaName.value = ''; search() }
function filterAudios() { audioPage.value = 1; void loadAudios() }
async function confirmReplacement() {
  if (!base.value || !selected.value) return
  const page = await dubbingApi.audios(selected.value.id, { current: 1, size: 1 })
  await ElMessageBox.confirm(`替换基础声音会删除原基础声音及 ${page.total} 条克隆配音。已被分镜引用时需先解除引用。是否继续？`, '替换基础声音', { type: 'warning', confirmButtonText: '继续替换' })
}
function openCreate(method: 'DESIGN' | 'CLONE', source?: AudioView) {
  uploadMode.value = false
  Object.assign(form, { method, name: method === 'DESIGN' ? '基础参考声音' : source ? `${source.audio.name}（新版本）` : '角色配音', text: source?.audio.text || '', voiceDesc: method === 'DESIGN' ? base.value?.audio.voiceDesc || '' : undefined, emotion: source?.audio.emotion || undefined, effects: source?.effects.map(e => ({ effectId: e.effectId, steps: e.steps })) || [], replaceBaseId: undefined })
  modelKey.value = availableModels.value[0] ? availableModels.value[0].providerId + '|' + availableModels.value[0].modelCode : ''
  createVisible.value = true
}
function openUpload() { openCreate('DESIGN'); uploadMode.value = true; uploadFile.value = undefined }
function chooseUpload(event: Event) { uploadFile.value = (event.target as HTMLInputElement).files?.[0] }
async function submitCreate() {
  if (!selected.value || !form.name.trim() || !form.text.trim()) { ElMessage.warning('请填写配音名称和台词'); return }
  if (uploadMode.value && !uploadFile.value) { ElMessage.warning('请选择音频文件'); return }
  if (!uploadMode.value && (!modelKey.value || form.method === 'DESIGN' && !form.voiceDesc?.trim())) { ElMessage.warning('请选择模型并填写音色描述'); return }
  if (uploadFile.value && uploadMode.value && uploadFile.value.size > 7 * 1024 * 1024) { ElMessage.warning('音频最大7 MiB'); return }
  if (form.method === 'DESIGN' && base.value) { try { await confirmReplacement() } catch { return } }
  saving.value = true
  try {
    if (uploadMode.value && uploadFile.value) {
      const data = new FormData(); data.append('file', uploadFile.value); data.append('name', form.name); data.append('text', form.text); if (base.value) data.append('replaceBaseId', base.value.audio.id)
      await dubbingApi.upload(selected.value.id, data)
    } else {
      const [providerId, modelCode] = modelKey.value.split('|'); if (!providerId || !modelCode) return
      await dubbingApi.create(selected.value.id, { ...form, providerId, modelCode, emotion: form.method === 'CLONE' ? form.emotion || undefined : undefined, effects: form.method === 'CLONE' ? form.effects : [], replaceBaseId: form.method === 'DESIGN' ? base.value?.audio.id : undefined })
    }
    createVisible.value = false; audioPage.value = 1; await loadAudios(); ElMessage.success(uploadMode.value ? '基础声音已上传' : '生成任务已提交，可在任务中心查看')
  } finally { saving.value = false }
}
function openEffects(item: AudioView) {
  editing.value = item; editingNames.value = Object.fromEntries(item.effects.map(e => [e.effectId, e.name])); editingEffects.value = item.effects.map(e => ({ effectId: e.effectId, steps: JSON.parse(JSON.stringify(e.steps)) })); effectPreview.value = ''; effectsVisible.value = true
}
watch(editingEffects, () => { effectPreview.value = ''; ++previewVersion }, { deep: true })
async function previewEffects() {
  if (!editing.value) return
  const version = previewVersion; previewing.value = true
  try { const data = new FormData(); data.append('audioId', editing.value.audio.id); data.append('steps', JSON.stringify(editingEffects.value.flatMap(e => e.steps || []))); const result = await dubbingApi.preview(data); if (version === previewVersion) effectPreview.value = result.audioUrl } finally { previewing.value = false }
}
async function saveEffects() {
  if (!editing.value) return
  saving.value = true
  try { await dubbingApi.updateEffects(editing.value.audio.id, editing.value.audio.revision, editingEffects.value); effectsVisible.value = false; await loadAudios(); ElMessage.success(editingEffects.value.length ? '特效处理已提交' : '已清空特效，使用克隆原声') } finally { saving.value = false }
}
async function clearEffects(item: AudioView) { await dubbingApi.updateEffects(item.audio.id, item.audio.revision, []); stopAudio(); await loadAudios(); ElMessage.success('已清空特效，直接播放克隆原声') }
async function retry(item: AudioView) { await dubbingApi.retry(item.audio.id, item.audio.revision); await loadAudios() }
async function remove(item: AudioView) {
  let message = `删除「${item.audio.name}」及其音频文件？`
  if (item.audio.kind === 'BASE' && selected.value) { const page = await dubbingApi.audios(selected.value.id, { size: 1, current: 1 }); message = `删除基础声音及全部 ${page.total} 条克隆配音？该操作会同时删除原始和特效音频文件。` }
  try { await ElMessageBox.confirm(message, '删除配音', { type: 'warning' }) } catch { return }
  await dubbingApi.remove(item.audio.id, item.audio.revision); stopAudio(); await loadAudios()
}
async function cardAction(command: string, item: AudioView) {
  if (command === 'delete') return remove(item)
  if (command === 'regenerate') return openCreate('CLONE', item)
  if (command === 'rename') {
    let result; try { result = await ElMessageBox.prompt('配音名称', '修改名称', { inputValue: item.audio.name, inputValidator: v => !!v?.trim() && v.length <= 128 || '请输入1至128字名称' }) } catch { return }
    await dubbingApi.rename(item.audio.id, item.audio.revision, result.value); await loadAudios()
  }
}
async function tick() { if (disposed) return; try { if (selected.value && !saving.value && !audioLoading.value) await loadAudios(true) } catch { /* Request interceptor reports errors; polling continues after recovery. */ } finally { if (!disposed) poll = setTimeout(tick, 5000) } }
watch(() => route.query.characterId, id => { if (typeof id === 'string' && id !== selected.value?.id) void loadCharacters(id) })
onMounted(async () => {
  const results = await Promise.allSettled([dictCommonApi.getDataByType('voice_emotion'), dubbingApi.effectOptions(), dubbingApi.filters(), dubbingApi.models()])
  if (results[0].status === 'fulfilled') emotions.value = results[0].value
  if (results[1].status === 'fulfilled') allEffects.value = results[1].value
  if (results[2].status === 'fulfilled') filters.value = results[2].value
  if (results[3].status === 'fulfilled') models.value = results[3].value
  try { await loadCharacters(typeof route.query.characterId === 'string' ? route.query.characterId : undefined) } finally { if (!disposed) poll = setTimeout(tick, 5000) }
})
onBeforeUnmount(() => { disposed = true; ++characterRequest; ++audioRequest; ++previewVersion; clearTimeout(poll); stopAudio() })
</script>

<style scoped>
.dubbing-center{max-width:1700px;margin:auto}.page-header{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:24px}.eyebrow{font-size:11px;letter-spacing:.18em;font-weight:700;color:var(--el-color-primary);margin-bottom:6px}h1{font-size:28px;font-weight:700}h2{font-size:22px;font-weight:650}h3{font-size:15px;font-weight:600}.subtitle{font-size:13px;color:var(--el-text-color-secondary);margin-top:8px}.search-bar{padding:18px 20px 0;border:1px solid var(--el-border-color-light);border-radius:14px;background:var(--el-bg-color);margin-bottom:18px}.studio-columns{display:grid;grid-template-columns:300px minmax(0,1fr);gap:20px;align-items:start}.character-panel,.audio-panel{background:var(--el-bg-color);border:1px solid var(--el-border-color-light);border-radius:16px;padding:18px;min-width:0}.panel-heading{display:flex;justify-content:space-between;gap:12px;align-items:center;margin-bottom:18px}.panel-heading span{font-size:11px;color:var(--el-text-color-secondary)}.character-panel>.panel-heading{align-items:start;flex-direction:column;gap:4px}.character-row{display:flex;gap:12px;width:100%;padding:14px 10px;text-align:left;border:1px solid transparent;border-radius:12px;margin:5px 0;cursor:pointer}.character-row:hover{background:var(--el-fill-color-light)}.character-row.selected{border-color:var(--el-color-primary-light-5);background:var(--el-color-primary-light-9)}.avatar{display:flex;align-items:center;justify-content:center;width:40px;height:40px;flex-shrink:0;border-radius:12px;background:var(--el-fill-color-dark);font-size:17px;color:var(--el-color-primary)}.character-info{display:flex;flex-direction:column;gap:4px;min-width:0}.character-info strong{font-size:14px}.character-info small{font-size:11px;color:var(--el-text-color-secondary);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.character-info .ready-label{color:var(--el-color-success)}.character-panel>.el-pagination{margin-top:18px;justify-content:center}.header-actions{display:flex;flex-wrap:wrap;justify-content:end;gap:8px}.header-actions .el-button+.el-button{margin-left:0}.base-card{padding:20px;border:1px solid var(--el-color-primary-light-7);border-radius:14px;background:var(--el-color-primary-light-9);margin-bottom:24px}.card-title{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.card-title>span:not(.el-tag){font-size:12px;color:var(--el-text-color-secondary)}.base-card h3{margin-top:12px}.script-text{font-size:13px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere;color:var(--el-text-color-regular);margin:12px 0;max-height:150px;overflow:auto}.card-footer{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-top:12px;font-size:12px;color:var(--el-text-color-secondary)}audio{width:100%;height:38px}.replacement-status{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:12px 0;margin-bottom:12px}.audio-filters{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:18px}.audio-filters>strong{margin-right:auto;white-space:nowrap}.audio-filters small{font-weight:400;color:var(--el-text-color-secondary);margin-left:4px}.audio-filters>.el-select{width:190px}.audio-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(285px,1fr));gap:16px}.audio-card{display:flex;flex-direction:column;padding:18px;border:1px solid var(--el-border-color-light);border-radius:14px}.audio-card .card-title{justify-content:space-between}.tags{display:flex;gap:6px;flex-wrap:wrap;margin-top:12px}.muted,.empty-base{font-size:12px;color:var(--el-text-color-secondary);line-height:1.7}.empty-base{margin-top:14px}.error-text{font-size:12px;color:var(--el-color-danger);margin-top:10px}.audio-meta{font-size:11px;color:var(--el-text-color-secondary);margin-top:auto;padding-top:16px}.card-actions{display:flex;gap:5px;align-items:center;flex-wrap:wrap;margin-top:14px;padding-top:12px;border-top:1px solid var(--el-border-color-extra-light)}.card-actions .el-button+.el-button{margin-left:0}.audio-pagination{margin-top:24px;justify-content:end}.dialog-alert{margin-bottom:20px}.preview-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:20px}.preview-grid p{font-size:12px;margin-bottom:10px}details{font-size:11px;color:var(--el-text-color-secondary);margin-top:8px}summary{cursor:pointer;margin-bottom:8px}
@media(max-width:1100px){.studio-columns{grid-template-columns:240px minmax(0,1fr)}.character-heading{align-items:start;flex-direction:column}.header-actions{justify-content:start}}@media(max-width:760px){.studio-columns{grid-template-columns:1fr}.page-header{align-items:start;flex-direction:column}.character-panel{max-height:340px;overflow:auto}.preview-grid{grid-template-columns:1fr}.search-bar :deep(.el-form-item){margin-right:0;width:100%}.audio-filters>.el-select{width:100%}.audio-pagination{justify-content:center}}
</style>
