<template>
  <el-dialog
    v-model="visible"
    title="🎙️ AI 角色专属音色设计工坊"
    width="740px"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
    class="voice-design-dialog"
  >
    <div class="space-y-4 max-h-[72vh] overflow-y-auto pr-1">
      <!-- 1. 当前角色基础设定上下文 -->
      <div class="bg-slate-50 border border-slate-200 rounded-xl p-3.5 space-y-2.5">
        <div class="flex items-center justify-between">
          <span class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
            <span>🎭</span> 角色基础人设上下文
          </span>
          <span class="text-[11px] text-slate-400">基于原著人设自动引导声音特质</span>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-3 gap-2 text-xs">
          <div class="bg-white p-2 rounded border border-slate-100">
            <span class="text-slate-400 block mb-0.5">姓名与定位</span>
            <span class="font-medium text-slate-800">
              {{ currentContext.name || '未命名' }} ({{ currentContext.gender || '未知性别' }} · {{ currentContext.roleType || '主角' }})
            </span>
          </div>
          <div class="bg-white p-2 rounded border border-slate-100 md:col-span-2">
            <span class="text-slate-400 block mb-0.5">内在性格灵魂</span>
            <span class="font-medium text-slate-800 line-clamp-1">
              {{ currentContext.personality || '暂未填写' }}
            </span>
          </div>
          <div v-if="currentContext.appearanceDesc" class="bg-white p-2 rounded border border-slate-100 md:col-span-3">
            <span class="text-slate-400 block mb-0.5">中文外貌描述 (原著视觉 SSOT)</span>
            <span class="font-medium text-slate-800 line-clamp-2">
              {{ currentContext.appearanceDesc }}
            </span>
          </div>
        </div>
      </div>

      <!-- 2. AI 提供商与 TTS 语音模型引擎配置 -->
      <div class="bg-slate-50 border border-slate-200 rounded-xl p-3.5 space-y-2.5">
        <div class="flex items-center justify-between">
          <span class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
            <span>⚙️</span> TTS 语音模型引擎配置
          </span>
          <span class="text-[11px] text-slate-400">支持灵活选择 AI 提供商与专属语音模型规格</span>
        </div>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
          <div>
            <label class="block text-[11px] font-semibold text-slate-700 mb-1">
              🏢 AI 提供商 (Provider)
            </label>
            <el-select
              v-model="selectedProviderId"
              placeholder="系统默认提供商"
              clearable
              size="small"
              class="w-full"
              @change="handleProviderChange"
            >
              <el-option
                v-for="p in enabledProviders"
                :key="String(p.id)"
                :label="p.providerName"
                :value="String(p.id)"
              />
            </el-select>
          </div>
          <div>
            <label class="block text-[11px] font-semibold text-slate-700 mb-1">
              🎙️ TTS 语音模型代码 (Model Code)
            </label>
            <el-select
              v-model="selectedModelCode"
              placeholder="选择或输入模型代码"
              clearable
              filterable
              allow-create
              default-first-option
              size="small"
              class="w-full"
            >
              <el-option
                v-for="m in models"
                :key="m.modelCode"
                :label="`${m.modelName} (${m.modelCode})`"
                :value="m.modelCode"
              />
              <el-option-group v-if="!models.some(m => m.modelCode === 'mimo-v2.5-tts-voicedesign')" label="推荐预设模型">
                <el-option label="MiMo-V2.5-TTS 声音设计 (mimo-v2.5-tts-voicedesign)" value="mimo-v2.5-tts-voicedesign" />
              </el-option-group>
            </el-select>
          </div>
        </div>
      </div>

      <!-- 3. 音色设计配置核心卡片 -->
      <div class="p-4 rounded-xl border border-indigo-100 bg-gradient-to-br from-indigo-50/30 via-white to-sky-50/20 shadow-xs space-y-3.5">
        <!-- 音色设计提示词 -->
        <div>
          <div class="flex items-center justify-between mb-1">
            <label class="text-xs font-semibold text-gray-800 flex items-center gap-1">
              <span>🗣️</span> 音色设计提示词 (Voice Prompt)
              <span class="text-red-500">*</span>
            </label>
            <el-button link type="primary" size="small" class="!text-xs" @click="handleAutoFillVoiceDesc">
              💡 根据性格人设智能提炼
            </el-button>
          </div>
          <el-input
            v-model="designForm.voiceDesc"
            type="textarea"
            :rows="3"
            placeholder="描述角色的年龄、音质、语气、特点（如：28岁成熟冷峻商界霸总男声，声线低沉磁性，咬字克制稳定，带微弱烟嗓，情绪内敛有压迫感...）"
          />
          <!-- 快捷声线预设标签 -->
          <div class="flex flex-wrap items-center gap-1.5 mt-2">
            <span class="text-[11px] text-gray-400">推荐声线标签:</span>
            <el-tag
              v-for="tag in voicePresetTags"
              :key="tag"
              size="small"
              effect="plain"
              class="cursor-pointer hover:bg-indigo-50 transition-colors !text-[11px] !py-0 !px-1.5"
              @click="handleAppendVoiceTag(tag)"
            >
              + {{ tag }}
            </el-tag>
          </div>
        </div>

        <!-- 试音参考台词 -->
        <div>
          <div class="flex items-center justify-between mb-1">
            <label class="text-xs font-semibold text-gray-800 flex items-center gap-1">
              <span>📜</span> 试音参考台词文本
            </label>
            <span class="text-[11px] text-gray-400">用于母音打样生成时朗读示范</span>
          </div>
          <el-input
            v-model="designForm.voiceSampleText"
            type="textarea"
            :rows="2"
            placeholder="试音台词（如：在这个商界，没有永恒的盟友，只有不可撼动的利益。）"
            clearable
          />
          <!-- 快捷台词模板 -->
          <div class="flex flex-wrap items-center gap-1.5 mt-1.5">
            <span class="text-[11px] text-gray-400">快速示范:</span>
            <el-button
              v-for="(quote, idx) in sampleQuotes"
              :key="idx"
              size="small"
              text
              type="info"
              class="!text-[11px] !px-1.5 !py-0 !h-6"
              @click="designForm.voiceSampleText = quote.text"
            >
              {{ quote.label }}
            </el-button>
          </div>
        </div>

        <!-- 生成专属音色动作按钮 -->
        <div class="pt-1 flex items-center justify-between">
          <div class="text-[11px] text-gray-400">
            基于多模态大模型智能合成专属声音母音 (MiMo-TTS)
          </div>
          <el-button
            type="primary"
            :loading="voiceDesigning"
            @click="handleDesignVoice"
          >
            {{ designForm.voiceSampleUrl ? '🔄 重新生成专属母音' : '⚡ 生成角色专属母音' }}
          </el-button>
        </div>
      </div>

      <!-- 3. 母音样音播放与管理卡片 -->
      <div v-if="designForm.voiceSampleUrl" class="p-3.5 rounded-xl bg-white border border-emerald-200 shadow-xs space-y-2.5">
        <div class="flex items-center justify-between">
          <span class="text-xs font-semibold text-emerald-800 flex items-center gap-1.5">
            <span>✓</span> 专属母音已生成/就绪
            <span v-if="voiceDuration" class="text-[11px] text-emerald-600 font-mono">({{ voiceDuration }}s)</span>
          </span>
          <div class="flex items-center gap-2">
            <el-upload
              action="#"
              :show-file-list="false"
              :http-request="handleUploadCustomVoice"
              accept="audio/*"
            >
              <el-button size="small" text type="primary">
                📁 上传替换本地音频
              </el-button>
            </el-upload>
            <el-button size="small" text type="danger" @click="handleClearVoiceSample">
              清除
            </el-button>
          </div>
        </div>

        <div class="flex items-center gap-3 p-2.5 rounded-lg bg-emerald-50/50 border border-emerald-100">
          <el-button
            circle
            :type="isPlayingVoice ? 'danger' : 'success'"
            class="!w-9 !h-9"
            @click="togglePlayVoiceSample"
          >
            {{ isPlayingVoice ? '⏸' : '▶' }}
          </el-button>
          <div class="overflow-hidden flex-1">
            <div class="text-xs font-mono text-emerald-900 truncate" :title="designForm.voiceSampleUrl">
              {{ designForm.voiceSampleUrl }}
            </div>
            <div class="text-[11px] text-emerald-600 mt-0.5">
              {{ isPlayingVoice ? '正在试听母音音频...' : '点击左侧播放键试听生成的专属母音' }}
            </div>
          </div>
        </div>
      </div>

      <!-- 4. 备用固定音色 ID -->
      <div class="bg-gray-50 border border-gray-200/80 rounded-xl p-3">
        <label class="text-xs font-semibold text-gray-700 block mb-1">
          备用: 第三方固定音色 ID (可选)
        </label>
        <el-input
          v-model="designForm.voiceId"
          placeholder="如：zh-CN-YunxiNeural，选填，当不使用克隆母音时回退为固定音色"
          size="small"
          clearable
        >
          <template #prepend>
            <span class="text-xs text-gray-500">固定音色ID</span>
          </template>
        </el-input>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-between">
        <div class="text-xs text-gray-400">
          {{ designForm.voiceSampleUrl ? '已绑定母音样音，点击确认采纳回填至人物表单' : '尚未生成母音，也可先保存提示词设定' }}
        </div>
        <div class="flex gap-2">
          <el-button @click="handleCancel">取消</el-button>
          <el-button type="primary" @click="handleConfirmApply">
            确定采纳
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, onBeforeUnmount } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import { voiceApi } from '@/api/voice'
import { assetApi } from '@/api/res-asset'
import { aiProviderApi } from '@/api/ai-provider'
import type { ResCharacter } from '@/types/resource'
import type { AiProviderVO, AiModel } from '@/types/ai-provider'

const emit = defineEmits<{
  (e: 'apply', data: {
    voiceDesc: string
    voiceSampleText: string
    voiceSampleUrl: string
    voiceId: string
    duration?: number | null
  }): void
}>()

const visible = ref(false)
const voiceDesigning = ref(false)
const isPlayingVoice = ref(false)
const voiceDuration = ref<number | null>(null)
let currentAudio: HTMLAudioElement | null = null

// AI 提供商与模型列表状态
const enabledProviders = ref<AiProviderVO[]>([])
const models = ref<AiModel[]>([])
const selectedProviderId = ref<string | undefined>()
const selectedModelCode = ref<string>('mimo-v2.5-tts-voicedesign')

const currentContext = reactive({
  id: undefined as number | string | undefined,
  name: '',
  gender: '',
  ageGroup: '',
  roleType: '',
  personality: '',
  appearanceDesc: ''
})

const designForm = reactive({
  voiceDesc: '',
  voiceSampleText: '',
  voiceSampleUrl: '',
  voiceId: ''
})

const voicePresetTags = [
  '冷酷霸总', '清冷御姐', '阳光少年', '元气甜美',
  '苍老沙哑', '儒雅知性', '阴鸷反派', '沉稳长者',
  '慵懒魅惑', '铁血刚毅', '活泼灵动', '深沉腹黑'
]

const sampleQuotes = [
  { label: '霸气商战', text: '在这个商界，没有永恒的盟友，只有不可撼动的利益。' },
  { label: '冷漠决绝', text: '我不在乎过程如何曲折，我只要看到最终的结果。' },
  { label: '温情鼓励', text: '无论发生什么，明天一定会有新的希望在等待我们。' },
  { label: '自我介绍', text: '我是角色本尊，这就是我的专属真实声音。' }
]

async function loadProviders() {
  try {
    const list = await aiProviderApi.getListEnabled()
    enabledProviders.value = list || []
    if (selectedProviderId.value) {
      const exists = enabledProviders.value.some(p => String(p.id) === String(selectedProviderId.value))
      if (!exists) {
        selectedProviderId.value = undefined
      }
    }
    // 智能优选：若未选，优先选中支持语音/TTS 的提供商（如 Xiaomi），避免误选到纯生图的本地网关
    if (!selectedProviderId.value && enabledProviders.value.length > 0) {
      const ttsPreferred = enabledProviders.value.find(p =>
        /xiaomi|mimo|tts|voice|audio|语音/i.test(p.providerName || '') ||
        /xiaomi|mimo|tts|voice|audio|语音/i.test(p.providerCode || '')
      )
      selectedProviderId.value = ttsPreferred ? String(ttsPreferred.id) : String(enabledProviders.value[0].id)
    }
    if (selectedProviderId.value) {
      await handleProviderChange(selectedProviderId.value)
    }
  } catch (err) {
    console.error('加载 AI 提供商列表失败', err)
  }
}

async function handleProviderChange(pid?: number | string) {
  models.value = []
  if (!pid) return
  try {
    const list = await aiProviderApi.getModelList(pid, 'TTS,AUDIO,VOICE')
    let validModels = (list || []).filter(m => m.status === 1)
    if (validModels.length === 0) {
      const allList = await aiProviderApi.getModelList(pid)
      validModels = (allList || []).filter(m => m.status === 1)
    }
    models.value = validModels
    // 优先匹配 mimo-v2.5-tts-voicedesign，或者保留已有有效选择
    const targetModel = models.value.find(m => m.modelCode === 'mimo-v2.5-tts-voicedesign')
      || models.value.find(m => m.modelCode === selectedModelCode.value)
      || models.value[0]
    if (targetModel) {
      selectedModelCode.value = targetModel.modelCode
    }
  } catch (e) {
    console.error('加载模型列表失败:', e)
  }
}

function open(character: Partial<ResCharacter>) {
  currentContext.id = character.id
  currentContext.name = character.canonicalName || character.name || character.displayName || ''
  currentContext.gender = character.gender || 'UNKNOWN'
  currentContext.ageGroup = character.ageGroup || 'YOUTH'
  currentContext.roleType = character.roleType || 'PROTAGONIST'
  currentContext.personality = character.personality || ''
  currentContext.appearanceDesc = character.appearanceDesc || ''

  designForm.voiceDesc = character.voiceDesc || ''
  designForm.voiceSampleText = character.voiceSampleText || (currentContext.name ? `我是${currentContext.name}，这就是我的真实声音。` : '')
  designForm.voiceSampleUrl = character.voiceSampleUrl || ''
  designForm.voiceId = character.voiceId || ''

  stopAudio()
  visible.value = true
  loadProviders()
}

function handleAppendVoiceTag(tag: string) {
  if (!designForm.voiceDesc) {
    designForm.voiceDesc = tag
  } else if (!designForm.voiceDesc.includes(tag)) {
    designForm.voiceDesc = `${designForm.voiceDesc}，${tag}`
  }
}

function handleAutoFillVoiceDesc() {
  const genderMap: Record<string, string> = { MALE: '男声', FEMALE: '女声', OTHER: '中性声音', UNKNOWN: '声音' }
  const ageMap: Record<string, string> = { TEENAGER: '少年青涩', YOUTH: '青年清爽', MIDDLE_AGED: '中青年沉稳', ELDERLY: '苍老沧桑' }
  const gender = genderMap[currentContext.gender] || '声音'
  const age = ageMap[currentContext.ageGroup] || ''
  const personality = currentContext.personality ? `内在性格${currentContext.personality}` : ''
  const promptParts = [age, gender, personality, '声线质感清晰自然，咬字富有感染力与戏剧张力'].filter(Boolean)
  designForm.voiceDesc = promptParts.join('，')
  if (!designForm.voiceSampleText) {
    designForm.voiceSampleText = `我是${currentContext.name || '该角色'}，这就是我的真实声音。`
  }
  ElMessage.success('已根据角色人设提炼音色提示词')
}

async function handleDesignVoice() {
  if (!designForm.voiceDesc && !currentContext.personality) {
    return ElMessage.warning('请先输入音色设计提示词或角色性格')
  }
  try {
    voiceDesigning.value = true
    const voiceDesc = designForm.voiceDesc || currentContext.personality || ''
    const sampleText = designForm.voiceSampleText || `我是${currentContext.name || '该角色'}，这就是我的真实声音。`

    // 严禁使用 Number(id) 转换 19 位雪花 ID，防止精度丢失导致后端 selectById 报提供商不存在！
    const effectiveProviderId = selectedProviderId.value && String(selectedProviderId.value).trim() !== ''
      ? String(selectedProviderId.value).trim()
      : undefined

    let res
    if (currentContext.id) {
      res = await voiceApi.designCharacterVoice(currentContext.id, {
        providerId: effectiveProviderId,
        modelCode: selectedModelCode.value,
        voiceDesc,
        sampleText,
        autoSave: true
      })
    } else {
      res = await voiceApi.previewVoiceDesign({
        providerId: effectiveProviderId,
        modelCode: selectedModelCode.value,
        voiceDesc,
        sampleText
      })
    }

    designForm.voiceSampleUrl = res.voiceSampleUrl
    designForm.voiceDesc = res.voiceDesc || voiceDesc
    designForm.voiceSampleText = res.sampleText || sampleText
    voiceDuration.value = res.duration || null
    if (res.modelCode) {
      selectedModelCode.value = res.modelCode
    }
    ElMessage.success('角色专属音色设计生成成功！')
  } catch (error: any) {
    ElMessage.error(error?.message || '音色设计失败')
  } finally {
    voiceDesigning.value = false
  }
}

function togglePlayVoiceSample() {
  if (!designForm.voiceSampleUrl) return
  if (isPlayingVoice.value && currentAudio) {
    currentAudio.pause()
    isPlayingVoice.value = false
    return
  }
  if (!currentAudio || currentAudio.src !== designForm.voiceSampleUrl) {
    currentAudio = new Audio(designForm.voiceSampleUrl)
    currentAudio.onended = () => {
      isPlayingVoice.value = false
    }
    currentAudio.onerror = () => {
      isPlayingVoice.value = false
      ElMessage.error('音频加载或播放失败')
    }
  }
  currentAudio.play()
  isPlayingVoice.value = true
}

function stopAudio() {
  if (currentAudio) {
    currentAudio.pause()
    currentAudio = null
  }
  isPlayingVoice.value = false
}

function handleClearVoiceSample() {
  stopAudio()
  designForm.voiceSampleUrl = ''
  voiceDuration.value = null
}

async function handleUploadCustomVoice(options: UploadRequestOptions) {
  try {
    const res = await assetApi.upload(options.file, 'audio')
    designForm.voiceSampleUrl = res.url
    ElMessage.success('自定义音频上传并绑定成功')
  } catch (error) {
    ElMessage.error('音频上传失败')
  }
}

function handleConfirmApply() {
  stopAudio()
  emit('apply', {
    voiceDesc: designForm.voiceDesc,
    voiceSampleText: designForm.voiceSampleText,
    voiceSampleUrl: designForm.voiceSampleUrl,
    voiceId: designForm.voiceId,
    duration: voiceDuration.value
  })
  visible.value = false
  ElMessage.success('已应用音色设计设定！')
}

function handleCancel() {
  stopAudio()
  visible.value = false
}

onBeforeUnmount(() => {
  stopAudio()
})

defineExpose({ open })
</script>

<style scoped>
:deep(.voice-design-dialog .el-dialog__body) {
  padding: 16px 20px;
}
</style>
