<template>
  <el-dialog :model-value="modelValue" :title="replacing ? '替换参考音频' : '从配音中心选择参考音频'" width="min(1180px, 96vw)" top="4vh" class="voice-audio-picker" append-to-body destroy-on-close :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)" @closed="stopAudio">
    <el-alert v-if="!dramaId || !characters.length" title="当前短剧没有可选角色，请先在短剧中配置角色。" type="info" :closable="false" />
    <div v-else class="picker-columns">
      <aside v-loading="charactersLoading" class="role-panel">
        <el-input v-model="name" clearable placeholder="按人物名称查询" @keyup.enter="searchCharacters"><template #append><el-button @click="searchCharacters">查询</el-button></template></el-input>
        <p class="hint">当前短剧角色 · {{ characterTotal }} 位</p>
        <el-empty v-if="!rolePage.length" description="没有匹配角色" :image-size="50" />
        <button v-for="role in rolePage" :key="role.id" class="role-row" :class="{ active: role.id === characterId }" @click="selectCharacter(role)"><strong>{{ role.name }}</strong><span>{{ role.has_base ? '已建立基础声音' : '暂无基础声音' }}</span></button>
        <el-pagination v-model:current-page="characterPage" :total="characterTotal" :page-size="8" :pager-count="3" small layout="prev, pager, next" @current-change="loadCharacters" />
      </aside>
      <section v-loading="loading" class="audio-panel">
        <div class="audio-header"><strong>{{ currentRoleName || '选择角色' }}</strong><el-button text :disabled="!characterId" @click="goToCenter">前往配音中心</el-button></div>
        <div class="picker-filters">
          <el-select v-model="effectId" clearable filterable placeholder="特效标签" @change="searchAudios"><el-option v-for="effect in effects" :key="String(effect.id)" :value="String(effect.id)" :label="effect.name" /></el-select>
        </div>
        <p class="hint">基础声音固定显示；克隆音频按标签筛选。有特效时选择当前成品，无特效时选择原声。</p>
        <div class="picker-list">
          <article v-for="item in availableAudios" :key="item.audio.id" class="audio-item" :class="{ chosen: isChosen(item) }">
            <div class="audio-heading"><strong>{{ item.audio.name }}</strong><el-tag v-if="item.audio.kind === 'BASE'" size="small">基础参考声音</el-tag><el-tag v-if="item.audio.emotion" size="small" type="success">{{ item.audio.emotion }}</el-tag><el-tag v-for="effect in item.effects" :key="effect.effectId" size="small" type="warning">{{ effect.name }}</el-tag></div>
            <p>{{ item.audio.text }}</p>
            <div class="audio-controls"><audio v-if="item.playbackUrl" :src="item.playbackUrl" controls preload="none" @play="onlyPlay" /><span>{{ item.duration == null ? '时长待检测' : item.duration.toFixed(2) + ' 秒' }}</span><el-button :type="isChosen(item) ? 'success' : 'primary'" :disabled="!isChosen(item) && !usable(item)" @click="toggle(item)">{{ isChosen(item) ? '已选 · 取消' : '选择' }}</el-button></div>
            <p v-if="!usable(item)" class="hint">{{ item.audio.status !== 'READY' ? '音频尚未就绪，暂不可选' : '仅可选择时长已知且为2至15秒的音频' }}</p>
          </article>
          <el-empty v-if="!availableAudios.length" description="暂无可用配音，可前往配音中心创建" :image-size="55" />
        </div>
        <el-pagination v-model:current-page="current" :total="total" :page-size="6" layout="total, prev, pager, next" @current-change="loadAudios" />
      </section>
    </div>
    <section v-if="chosen.length" class="selection-panel">
      <h3>本次已选 {{ chosen.length }} 段 · 确认后合计 {{ finalDuration.toFixed(2) }} 秒</h3>
      <div v-for="item in chosen" :key="item.audioId" class="selection-row">
        <span>{{ item.characterName }} · {{ item.name }}</span>
        <el-select v-model="item.referenceType" placeholder="选择参考关系"><el-option v-for="option in REFERENCE_AUDIO_TYPE_OPTIONS" :key="option.value" :value="option.value" :label="option.label + ' (' + option.value + ')'" /></el-select>
        <el-input v-model="item.referenceDescription" placeholder="说明复用或参考哪些内容" maxlength="1000" clearable />
        <el-button text type="danger" @click="chosen = chosen.filter(a => a.audioId !== item.audioId)">移除</el-button>
      </div>
    </section>
    <template #footer><span class="limit-hint">最多3段 · 单段2至15秒 · 总长不超过15秒</span><el-button @click="emit('update:modelValue', false)">取消</el-button><el-button type="primary" :disabled="!chosen.length" @click="confirm">确认选择</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { dubbingApi, type AudioView, type Effect, type Character } from '@/api/dubbing'
import { REFERENCE_AUDIO_TYPE_OPTIONS } from '@/constants/referenceMediaOptions'
import type { ShotRefAudio } from '@/types/drama'
const props = defineProps<{
  modelValue: boolean
  dramaId?: string | number
  characters: { id: string | number; name: string; dramaId?: string | number }[]
  preferredCharacterId?: string | number
  selectedAudios?: ShotRefAudio[]
  replaceIndex?: number | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; select: [audios: ShotRefAudio[]] }>()
const router = useRouter()
const name = ref(''), characterPage = ref(1), characterTotal = ref(0), rolePage = ref<Character[]>([]), charactersLoading = ref(false)
const characterId = ref(''), effectId = ref(''), current = ref(1), total = ref(0), base = ref<AudioView | null>(null), audios = ref<AudioView[]>([]), loading = ref(false)
const effects = ref<Effect[]>([]), chosen = ref<ShotRefAudio[]>([])
let roleSequence = 0, audioSequence = 0
const replacing = computed(() => props.replaceIndex != null)
const scope = computed(() => ({ dramaId: props.dramaId ? String(props.dramaId) : undefined, characterIds: [...new Set(props.characters.map(c => String(c.id)))] }))
const currentRoleName = computed(() => props.characters.find(c => String(c.id) === characterId.value)?.name)
const availableAudios = computed(() => [...(base.value ? [base.value] : []), ...audios.value])
const retained = computed(() => (props.selectedAudios || []).filter((_, index) => index !== props.replaceIndex))
const finalDuration = computed(() => [...retained.value, ...chosen.value].reduce((sum, item) => sum + (item.duration || 0), 0))
const usable = (item: AudioView) => item.audio.status === 'READY' && !!item.playbackUrl && item.duration != null && item.duration >= 2 && item.duration <= 15
const isChosen = (item: AudioView) => chosen.value.some(a => a.audioId === item.audio.id)
async function loadCharacters() {
  if (!props.dramaId || !props.characters.length) return
  const request = ++roleSequence; charactersLoading.value = true
  try {
    const result = await dubbingApi.pickerCharacters({ ...scope.value, name: name.value || undefined, current: characterPage.value, size: 8, preferredCharacterId: props.preferredCharacterId ? String(props.preferredCharacterId) : undefined })
    if (request !== roleSequence || !props.modelValue) return
    rolePage.value = result.records; characterTotal.value = Number(result.total)
    if (!result.records.some(role => role.id === characterId.value)) {
      const role = result.records.find(r => r.id === String(props.preferredCharacterId)) || result.records[0]
      if (role) await selectCharacter(role)
      else { ++audioSequence; characterId.value = ''; base.value = null; audios.value = []; total.value = 0; loading.value = false }
    }
  } finally { if (request === roleSequence) charactersLoading.value = false }
}
function searchCharacters() { characterPage.value = 1; void loadCharacters() }
async function selectCharacter(role: Character) {
  if (characterId.value === role.id) return
  stopAudio(); characterId.value = role.id; current.value = 1; effectId.value = ''; base.value = null; audios.value = []; await loadAudios()
}
async function loadAudios() {
  if (!characterId.value) return
  const request = ++audioSequence, selectedId = characterId.value; loading.value = true
  try {
    const [reference, page] = await Promise.all([dubbingApi.pickerBase(selectedId, scope.value), dubbingApi.pickerAudios(selectedId, { ...scope.value, current: current.value, size: 6, effectIds: effectId.value ? [effectId.value] : [] })])
    if (request === audioSequence && props.modelValue) { base.value = reference; audios.value = page.records; total.value = Number(page.total) }
  } finally { if (request === audioSequence) loading.value = false }
}
function searchAudios() { current.value = 1; void loadAudios() }
function stopAudio() { document.querySelectorAll<HTMLAudioElement>('.voice-audio-picker audio').forEach(a => a.pause()) }
function onlyPlay(event: Event) { document.querySelectorAll<HTMLAudioElement>('audio').forEach(a => { if (a !== event.target) a.pause() }) }
function toggle(item: AudioView) {
  if (isChosen(item)) { chosen.value = chosen.value.filter(a => a.audioId !== item.audio.id); return }
  if (!usable(item)) return
  if (retained.value.some(a => a.audioUrl === item.playbackUrl)) { ElMessage.warning('该音频已占用其他槽位'); return }
  const original = replacing.value ? props.selectedAudios?.[props.replaceIndex!] : undefined
  const selected: ShotRefAudio = { id: original?.id || crypto.randomUUID(), audioId: item.audio.id, characterId: item.audio.characterId, characterName: currentRoleName.value, name: item.audio.name, audioUrl: item.playbackUrl!, duration: item.duration, text: item.audio.text, referenceType: original?.referenceType || 'reference', referenceDescription: original?.referenceDescription }
  const next = replacing.value ? [selected] : [...chosen.value, selected]
  if (retained.value.length + next.length > 3 || [...retained.value, ...next].reduce((sum, a) => sum + (a.duration || 0), 0) > 15) { ElMessage.warning('最多3段音频，参考音频总长不得超过15秒'); return }
  chosen.value = next
}
function confirm() {
  if (!chosen.value.length || finalDuration.value > 15 || retained.value.length + chosen.value.length > 3) return
  if (chosen.value.some(a => !REFERENCE_AUDIO_TYPE_OPTIONS.some(option => option.value === a.referenceType))) { ElMessage.warning('请为每条音频选择参考关系'); return }
  emit('select', chosen.value.map(a => ({ ...a }))); emit('update:modelValue', false)
}
function goToCenter() { emit('update:modelValue', false); void router.push({ path: '/dubbing/characters', query: { characterId: characterId.value } }) }
watch(() => props.modelValue, async visible => {
  ++roleSequence; ++audioSequence; stopAudio()
  if (!visible) return
  name.value = ''; characterPage.value = 1; current.value = 1; characterId.value = ''; rolePage.value = []; base.value = null; audios.value = []; chosen.value = []; total.value = 0
  await Promise.allSettled([loadCharacters(), dubbingApi.effectOptions().then(data => { effects.value = data })])
})
onBeforeUnmount(() => { ++roleSequence; ++audioSequence; stopAudio() })
</script>
<style scoped>
.voice-audio-picker :deep(.el-dialog__body){max-height:76vh;overflow:auto}

.picker-columns{display:grid;grid-template-columns:240px minmax(0,1fr);gap:20px}.role-panel{max-height:54vh;overflow:auto;border-right:1px solid var(--el-border-color-light);padding-right:18px}.hint{font-size:12px;color:var(--el-text-color-secondary);margin:12px 0;line-height:1.6}.role-row{display:flex;flex-direction:column;gap:6px;text-align:left;width:100%;padding:14px 12px;border:1px solid transparent;border-radius:10px;margin:6px 0}.role-row.active{border-color:var(--el-color-primary-light-5);background:var(--el-color-primary-light-9)}.role-row strong{font-size:14px}.role-row span{font-size:11px;color:var(--el-text-color-secondary)}.role-panel>.el-pagination{margin-top:16px;justify-content:center}.audio-panel{min-width:0}.audio-header,.audio-heading,.audio-controls{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.audio-header{justify-content:space-between;margin-bottom:12px}.picker-filters{display:flex;gap:12px}.picker-filters .el-select{flex:1}.picker-list{min-height:160px;max-height:38vh;overflow:auto}.audio-item{border:1px solid var(--el-border-color-light);border-radius:12px;padding:14px;margin-bottom:12px}.audio-item.chosen{border-color:var(--el-color-primary)}.audio-heading strong{font-size:14px}.audio-item>p{font-size:12px;color:var(--el-text-color-secondary);margin:10px 0;white-space:pre-wrap}.audio-controls audio{flex:1;min-width:180px;height:36px}.audio-controls span{font-size:11px;color:var(--el-text-color-secondary)}.audio-panel>.el-pagination{justify-content:end;margin-top:12px}.selection-panel{border-top:1px solid var(--el-border-color-light);margin-top:20px;padding-top:18px}.selection-panel h3{font-size:13px;font-weight:600;margin-bottom:12px}.selection-row{display:grid;grid-template-columns:190px 240px 1fr 50px;gap:10px;align-items:center;margin:10px 0}.selection-row>span{font-size:12px}.limit-hint{font-size:12px;color:var(--el-text-color-secondary);margin-right:16px}@media(max-width:800px){.picker-columns,.selection-row{grid-template-columns:1fr}.role-panel{max-height:54vh;overflow:auto;border-right:0;border-bottom:1px solid var(--el-border-color-light);padding-right:0;padding-bottom:14px;max-height:260px;overflow:auto}.picker-filters{flex-wrap:wrap}.limit-hint{display:block;margin:0 0 12px}}
</style>
