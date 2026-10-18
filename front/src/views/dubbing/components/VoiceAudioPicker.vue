<template>
  <el-dialog :model-value="modelValue" title="从配音中心选择参考音频" width="min(820px, 95vw)" append-to-body destroy-on-close @update:model-value="emit('update:modelValue', $event)" @closed="stopAudio">
    <div class="picker-filters">
      <el-select v-model="characterId" filterable placeholder="选择角色" @change="changeCharacter"><el-option v-for="character in characters" :key="String(character.id)" :value="String(character.id)" :label="character.name" /></el-select>
      <el-select v-model="emotion" clearable placeholder="情绪标签" @change="search"><el-option v-for="option in emotions" :key="option.dictValue" :value="option.dictValue" :label="option.dictLabel" /></el-select>
      <el-select v-model="effectId" clearable filterable placeholder="特效标签" @change="search"><el-option v-for="effect in effects" :key="String(effect.id)" :value="String(effect.id)" :label="effect.name" /></el-select>
    </div>
    <el-radio-group v-model="usage"><el-radio value="VOICE_TIMBRE">仅参考音色</el-radio><el-radio value="DIALOGUE_REUSE">复用这段台词</el-radio></el-radio-group>
    <p class="hint">单段2至15秒，总长不超过15秒。选择配音会保存当前音频地址与实际时长。</p>
    <div v-loading="loading" class="picker-list">
      <div v-if="base" class="audio-item"><div><el-tag size="small">基础参考声音</el-tag><strong>{{ base.audio.name }}</strong><p>{{ base.audio.text }}</p></div><audio v-if="base.playbackUrl" :src="base.playbackUrl" controls preload="none" @play="onlyPlay" /><el-button :disabled="!usable(base) || usage === 'DIALOGUE_REUSE'" @click="choose(base)">选择</el-button></div>
      <div v-for="item in audios" :key="item.audio.id" class="audio-item"><div><strong>{{ item.audio.name }}</strong><el-tag v-if="item.audio.emotion" size="small">{{ item.audio.emotion }}</el-tag><el-tag v-for="effect in item.effects" :key="effect.effectId" size="small" type="warning">{{ effect.name }}</el-tag><p>{{ item.audio.text }}</p></div><audio v-if="item.playbackUrl" :src="item.playbackUrl" controls preload="none" @play="onlyPlay" /><el-button :disabled="!usable(item)" @click="choose(item)">选择</el-button></div>
      <el-empty v-if="!base && !audios.length" description="暂无可用配音，请先在配音中心创建" :image-size="65" />
    </div>
    <el-pagination v-model:current-page="current" :total="total" :page-size="6" layout="total, prev, pager, next" @current-change="load" />
  </el-dialog>
</template>
<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { dubbingApi, type AudioView, type Effect } from '@/api/dubbing'
import { dictCommonApi } from '@/api/dict'
import type { SysDictData } from '@/types/dict'
import type { ShotRefAudio } from '@/types/drama'
const props = defineProps<{ modelValue: boolean; characters: { id: string | number; name: string }[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; select: [audio: ShotRefAudio] }>()
const characterId = ref(''), emotion = ref(''), effectId = ref(''), usage = ref('VOICE_TIMBRE'), current = ref(1), total = ref(0), base = ref<AudioView | null>(null), audios = ref<AudioView[]>([]), loading = ref(false), effects = ref<Effect[]>([]), emotions = ref<SysDictData[]>([])
let sequence = 0
const usable = (audio: AudioView) => audio.audio.status === 'READY' && !!audio.playbackUrl && audio.duration != null && audio.duration >= 2 && audio.duration <= 15
async function load() { if (!characterId.value) return; const request = ++sequence; loading.value = true; try { const [reference, page] = await Promise.all([dubbingApi.base(characterId.value), dubbingApi.audios(characterId.value, { current: current.value, size: 6, emotions: emotion.value ? [emotion.value] : [], effectIds: effectId.value ? [effectId.value] : [] })]); if (request === sequence) { base.value = reference; audios.value = page.records; total.value = Number(page.total) } } finally { if (request === sequence) loading.value = false } }
function search() { current.value = 1; void load() }
function changeCharacter() { stopAudio(); base.value = null; audios.value = []; search() }
function stopAudio() { document.querySelectorAll<HTMLAudioElement>('.el-dialog audio').forEach(a => a.pause()) }
function onlyPlay(event: Event) { document.querySelectorAll<HTMLAudioElement>('audio').forEach(a => { if (a !== event.target) a.pause() }) }
function choose(item: AudioView) {
  if (!usable(item)) return
  const character = props.characters.find(c => String(c.id) === characterId.value)
  emit('select', { id: `dubbing_${item.audio.id}`, sourceType: item.audio.kind === 'BASE' ? 'CHARACTER' : 'TTS', characterId: characterId.value, characterName: character?.name, name: item.audio.name, audioUrl: item.playbackUrl!, duration: item.duration, text: item.audio.text, usageMode: usage.value })
}
watch(() => props.modelValue, async visible => {
  if (!visible) { ++sequence; stopAudio(); return }
  characterId.value = props.characters[0] ? String(props.characters[0].id) : ''; current.value = 1; emotion.value = ''; effectId.value = ''; base.value = null; audios.value = []; usage.value = 'VOICE_TIMBRE'
  await Promise.allSettled([load(), dubbingApi.effectOptions().then(data => { effects.value = data }), dictCommonApi.getDataByType('voice_emotion').then(data => { emotions.value = data })])
})
onBeforeUnmount(() => { ++sequence; stopAudio() })
</script>
<style scoped>
.picker-filters{display:flex;gap:10px;margin-bottom:16px}.picker-filters .el-select{flex:1;min-width:0}.hint{font-size:12px;color:var(--el-text-color-secondary);margin:12px 0}.picker-list{min-height:160px;max-height:460px;overflow:auto}.audio-item{display:grid;grid-template-columns:1fr 210px 60px;align-items:center;gap:14px;border:1px solid var(--el-border-color-light);border-radius:12px;padding:14px;margin-bottom:12px}.audio-item strong{font-size:13px;margin:0 6px}.audio-item p{font-size:12px;color:var(--el-text-color-secondary);margin-top:8px;max-height:55px;overflow:auto}.audio-item audio{width:100%;height:36px}.el-pagination{justify-content:end;margin-top:16px}@media(max-width:650px){.audio-item{grid-template-columns:1fr}.picker-filters{flex-wrap:wrap}.picker-filters .el-select{flex:auto;width:100%}}
</style>
