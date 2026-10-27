<template>
  <el-dialog :model-value="modelValue" :title="replaceIndex != null ? '替换参考音频' : '选择参考音频'" width="min(1180px, 96vw)" top="4vh" append-to-body destroy-on-close :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)">
    <div class="flex flex-wrap items-center justify-between gap-3 mb-4">
      <p class="text-xs text-[var(--text-muted)]">选择公共库或当前短剧音频；最多 {{ maxAudios }} 段，单段 2～15 秒，总长 ≤ 15 秒。</p>
      <el-button @click="voiceVisible = true">从配音中心选择角色声音</el-button>
    </div>
    <el-alert title="音频种类用于整理素材。请为当前镜头明确选择参考关系与用途；一个素材可以承担多个用途。" type="info" :closable="false" class="mb-4" />
    <p class="text-xs text-[var(--text-muted)] mb-4">如需使用背景音乐，请在参考说明与镜头提示词中明确音乐用途。</p>
    <div class="max-h-[48vh] overflow-auto">
      <AudioList v-if="modelValue" :key="String(dramaId || '0')" selection :drama-id="dramaId" :shot-id="shotId" @selected="choose" />
    </div>
    <div v-if="chosen" class="studio-card p-4 mt-4 space-y-3">
      <strong>{{ chosen.name }} · {{ audioKindLabel(chosen.audioKind) }}</strong>
      <audio :src="chosen.audioUrl" controls preload="metadata" class="w-full" />
      <el-select v-model="characterId" clearable filterable placeholder="关联角色（可选，音色或对白参考时可指定）" class="w-full">
        <el-option v-for="character in characters" :key="String(character.id)" :value="String(character.id)" :label="character.name" />
      </el-select>
      <el-select v-model="relation" placeholder="请选择参考关系" class="w-full">
        <el-option v-for="option in REFERENCE_AUDIO_TYPE_OPTIONS" :key="option.value" :value="option.value" :label="option.label" />
      </el-select>
      <p class="text-xs text-[var(--text-muted)]">{{ REFERENCE_AUDIO_TYPE_OPTIONS.find(option => option.value === relation)?.description }}</p>
      <el-input v-model="description" type="textarea" :rows="2" maxlength="1000" show-word-limit placeholder="明确参考哪些声音特征、复用哪个时间段或声音层，以及需要衔接的内容" />
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!chosen || !relation" @click="confirm">确认选择</el-button>
    </template>
  </el-dialog>
  <VoiceAudioPicker v-model="voiceVisible" :drama-id="dramaId" :characters="characters" :preferred-character-id="preferredCharacterId" :selected-audios="selectedAudios" :replace-index="replaceIndex" @select="selectVoices" />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AudioList from './AudioList.vue'
import VoiceAudioPicker from '@/views/dubbing/components/VoiceAudioPicker.vue'
import { audioKindLabel } from '@/constants/audioAssetOptions'
import { REFERENCE_AUDIO_TYPE_OPTIONS, type AudioReferenceType } from '@/constants/referenceMediaOptions'
import type { ResAudio } from '@/types/resource'
import type { ShotRefAudio } from '@/types/drama'

const props = withDefaults(defineProps<{
  modelValue: boolean
  dramaId?: string | number
  shotId?: string | number
  characters: { id: string | number; name: string; dramaId?: string | number }[]
  preferredCharacterId?: string | number
  selectedAudios?: ShotRefAudio[]
  replaceIndex?: number | null
  maxAudios?: number
}>(), { maxAudios: 3 })
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; select: [audios: ShotRefAudio[]] }>()
const chosen = ref<ResAudio>(), relation = ref<AudioReferenceType>(), description = ref(''), voiceVisible = ref(false)
const characterId = ref<string>()
const retained = computed(() => (props.selectedAudios || []).filter((_, index) => index !== props.replaceIndex))

function check(audios: ShotRefAudio[]) {
  const all = [...retained.value, ...audios]
  if (all.length > Math.min(3, props.maxAudios)) { ElMessage.warning('参考音频数量超过所选模型上限'); return false }
  if (audios.some(audio => audio.duration == null || audio.duration < 2 || audio.duration > 15)) { ElMessage.warning('H3 参考音频单段须为 2～15 秒，请先裁剪后上传'); return false }
  if (all.reduce((total, audio) => total + (audio.duration || 0), 0) > 15) { ElMessage.warning('参考音频总长不能超过 15 秒'); return false }
  if (new Set(all.map(audio => audio.audioUrl)).size !== all.length) { ElMessage.warning('同一音频不能重复占用槽位'); return false }
  return true
}
function choose(audio: ResAudio) {
  if (!check([{ audioUrl: audio.audioUrl, duration: audio.duration, referenceType: 'reference' }])) return
  chosen.value = audio
  const original = props.replaceIndex != null ? props.selectedAudios?.[props.replaceIndex] : undefined
  relation.value = original?.referenceType
  description.value = original?.referenceDescription || ''
  characterId.value = original?.characterId != null ? String(original.characterId) : undefined
}
function confirm() {
  if (!chosen.value || !relation.value) return
  const audio = chosen.value
  const original = props.replaceIndex != null ? props.selectedAudios?.[props.replaceIndex] : undefined
  const selected: ShotRefAudio = {
    id: original?.id || crypto.randomUUID(), resAudioId: String(audio.id), audioKind: audio.audioKind,
    audioUrl: audio.audioUrl, duration: audio.duration, name: audio.name, text: audio.text,
    language: audio.language, referenceType: relation.value, referenceDescription: description.value.trim() || undefined,
    characterId: characterId.value || undefined,
    characterName: props.characters.find(character => String(character.id) === characterId.value)?.name
  }
  if (!check([selected])) return
  emit('select', [selected]); emit('update:modelValue', false)
}
function selectVoices(audios: ShotRefAudio[]) {
  if (!check(audios)) return
  emit('select', audios); emit('update:modelValue', false)
}
watch(() => props.modelValue, () => { chosen.value = undefined; relation.value = undefined; description.value = ''; characterId.value = undefined; voiceVisible.value = false })
</script>
