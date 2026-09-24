<template>
  <el-dialog
    v-model="visible"
    :title="modalTitle"
    width="860px"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="!isStreaming"
    :show-close="!isStreaming"
    class="look-prompt-derive-dialog"
  >
    <div class="space-y-4 max-h-[72vh] overflow-y-auto pr-1">
      <!-- 1. 当前上下文横幅与配置 -->
      <div class="bg-slate-50 border border-slate-200 rounded-xl p-3.5 space-y-3">
        <div class="flex items-center justify-between">
          <span class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
            <span>👗</span> 当前造型与角色上下文 (服装与造型层)
          </span>
          <el-tag size="small" type="primary" effect="plain">
            {{ currentCharacter.name || '未命名角色' }} · {{ currentLook.lookName || '未命名造型' }} ({{ currentLook.lookType || 'DAILY' }})
          </el-tag>
        </div>

        <SkillSelector
          v-model="skillNames"
          :mode="executionMode"
          :disabled="isStreaming || isExportingPackage"
          @update:model-value="handleSkillChange"
        />

        <div class="grid grid-cols-1 md:grid-cols-3 gap-2 text-xs">
          <div class="bg-white p-2 rounded border border-slate-100">
            <span class="text-slate-400 block mb-0.5">所属角色与性别</span>
            <span class="font-medium text-slate-800">
              {{ currentCharacter.name || '未命名' }} ({{ currentCharacter.gender || 'UNKNOWN' }} · {{ currentCharacter.roleType || 'PROTAGONIST' }})
            </span>
          </div>
          <div class="bg-white p-2 rounded border border-slate-100">
            <span class="text-slate-400 block mb-0.5">造型名称与类型</span>
            <span class="font-medium text-slate-800">
              {{ currentLook.lookName || '未命名' }} ({{ currentLook.lookType || 'DAILY' }})
            </span>
          </div>
          <div class="bg-white p-2 rounded border border-slate-100">
            <span class="text-slate-400 block mb-0.5">角色基础稳定外貌 (参考)</span>
            <span class="font-medium text-slate-800 line-clamp-1">
              {{ currentCharacter.appearanceDesc || '暂无描述' }}
            </span>
          </div>
          <div class="bg-white p-2 rounded border border-slate-100 md:col-span-3">
            <span class="text-slate-400 block mb-0.5">造型设定与服饰细节描述 (designDesc - 核心依据)</span>
            <span class="font-medium text-slate-800 line-clamp-2">
              {{ currentLook.designDesc || '暂未填写（建议在造型设计描述中详尽填写衣着款式、材质配色、配饰细节等）' }}
            </span>
          </div>
        </div>

        <!-- 关联短剧信息 -->
        <div v-if="dramaInfo" class="flex items-center justify-between bg-indigo-50/80 border border-indigo-200/80 rounded-lg px-3 py-2 text-xs text-indigo-900">
          <div class="flex items-center gap-1.5 font-medium">
            <span>🎬</span>
            <span>关联短剧：《{{ dramaInfo.title }}》</span>
            <span class="text-indigo-300">|</span>
            <span class="text-indigo-600 font-normal">已自动继承短剧全局视觉画风与题材基调</span>
          </div>
          <el-tag size="small" type="success" effect="plain" class="!font-medium">画风联动中</el-tag>
        </div>

        <!-- 补充画风指导与大模型选择 -->
        <div class="space-y-2.5 pt-1 border-t border-slate-200/60">
          <!-- 生成方式切换 (双通道) -->
          <div class="bg-white rounded-lg p-2 border border-slate-200 flex flex-wrap items-center justify-between gap-2">
            <div class="flex items-center gap-2">
              <span class="text-[11px] font-semibold text-slate-700">⚡ 生成方式：</span>
              <el-radio-group
                v-model="executionMode"
                size="small"
                :disabled="isStreaming"
                @change="handleExecutionModeChange"
              >
                <el-radio-button value="API">⚡ API 自动生成</el-radio-button>
                <el-radio-button value="MANUAL">📋 外部 AI 手工生成 (无需配置 API)</el-radio-button>
              </el-radio-group>
            </div>
            <span class="text-[10px] text-slate-400">
              {{ executionMode === 'API' ? '使用系统配置的推理模型实时流式生成并解析' : '导出任务 Prompt 粘贴至 ChatGPT / Claude / Gemini，零 API 费用' }}
            </span>
          </div>

          <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label class="block text-[11px] font-semibold text-slate-700 mb-1">
                🎨 短剧全局画风预设 (stylePreset)
              </label>
              <DictSelect
                v-model="stylePreset"
                dict-type="drama_style_preset"
                placeholder="选择或继承短剧画风"
                size="small"
                clearable
                class="w-full"
                :disabled="isStreaming"
                @change="onStyleParamsChange"
              />
            </div>

            <!-- API 模式展示模型选择 -->
            <div v-if="executionMode === 'API'">
              <label class="block text-[11px] font-semibold text-slate-600 mb-1">
                🤖 文本推理模型 (可选)
              </label>
              <div class="flex gap-1.5">
                <el-select
                  v-model="providerId"
                  placeholder="默认供应商"
                  size="small"
                  class="flex-1"
                  clearable
                  :disabled="isStreaming"
                  @change="handleProviderChange"
                >
                  <el-option
                    v-for="p in providers"
                    :key="String(p.id)"
                    :label="p.providerName"
                    :value="String(p.id)"
                  />
                </el-select>
                <el-select
                  v-model="modelCode"
                  placeholder="模型代码"
                  size="small"
                  class="flex-1"
                  clearable
                  :disabled="isStreaming || !providerId"
                >
                  <el-option
                    v-for="m in models"
                    :key="m.modelCode"
                    :label="m.modelName || m.modelCode"
                    :value="m.modelCode"
                  />
                </el-select>
              </div>
            </div>

            <!-- MANUAL 模式引导 -->
            <div v-else class="flex items-center">
              <div class="bg-amber-50 border border-amber-200/70 rounded-lg p-2 text-[11px] text-amber-800 flex items-center gap-1.5 w-full">
                <span>💡</span>
                <span>手工模式无需消耗系统 Token 额度，导出任务包并在外部对话模型粘贴即可获得专业造型 Prompt。</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 2. 执行区域：根据 executionMode 显示对应工作区 -->
      <!-- A. API 自动生成模式工作区 -->
      <div v-if="executionMode === 'API'" class="space-y-3">
        <div v-if="isStreaming || streamText" class="border border-slate-200 rounded-xl overflow-hidden bg-slate-900 shadow-sm">
          <div class="flex items-center justify-between px-3 py-2 bg-slate-800 border-b border-slate-700 text-xs">
            <div class="flex items-center gap-2">
              <span class="w-2.5 h-2.5 rounded-full bg-emerald-500" :class="{ 'animate-ping': isStreaming }"></span>
              <span class="font-mono text-slate-200 font-semibold">AI 造型提示词实时推理控制台</span>
            </div>
            <div class="flex items-center gap-3">
              <span class="font-mono text-slate-400 text-[11px]">耗时: {{ elapsedSeconds }}s</span>
              <el-button
                v-if="isStreaming"
                type="danger"
                size="small"
                link
                @click="handleAbortStream"
              >
                停止生成
              </el-button>
            </div>
          </div>
          <pre
            ref="streamConsoleRef"
            class="p-3 font-mono text-xs text-emerald-400 bg-slate-950/80 max-h-48 overflow-y-auto whitespace-pre-wrap break-all leading-relaxed"
          >{{ streamText || '等待模型响应中...' }}</pre>
        </div>
      </div>

      <!-- B. MANUAL 外部 AI 手工生成工作区 -->
      <div v-else class="space-y-3">
        <div class="border border-slate-200 rounded-xl overflow-hidden bg-white">
          <div class="flex border-b border-slate-200 bg-slate-50">
            <button
              class="px-4 py-2 text-xs font-semibold border-b-2 transition-colors"
              :class="activeManualTab === 'prompt' ? 'border-primary text-primary bg-white' : 'border-transparent text-slate-500 hover:text-slate-800'"
              @click="activeManualTab = 'prompt'"
            >
              1. 导出造型任务 Prompt
            </button>
            <button
              class="px-4 py-2 text-xs font-semibold border-b-2 transition-colors"
              :class="activeManualTab === 'paste' ? 'border-primary text-primary bg-white' : 'border-transparent text-slate-500 hover:text-slate-800'"
              @click="activeManualTab = 'paste'"
            >
              2. 粘贴外部 AI 结果并解析
              <span v-if="validationInfo" class="ml-1 px-1.5 py-0.5 rounded text-[10px]" :class="validationInfo.errors.length > 0 ? 'bg-rose-100 text-rose-700' : 'bg-emerald-100 text-emerald-700'">
                {{ validationInfo.errors.length > 0 ? '校验异常' : '校验通过' }}
              </span>
            </button>
          </div>

          <!-- 子面板 1: 任务 Prompt 导出与复制 -->
          <div v-if="activeManualTab === 'prompt'" class="p-3 space-y-3">
            <div class="flex items-center justify-between">
              <div class="text-xs text-slate-600">
                包含造型设计描述、角色外貌上下文、短剧画风及输出 JSON 结构的完整标准化 Prompt。
              </div>
              <div class="flex gap-2">
                <el-button size="small" @click="handleViewJsonExample">查看输出 JSON 规范</el-button>
                <el-button
                  type="primary"
                  size="small"
                  :loading="isExportingPackage"
                  :disabled="!promptPackage?.combinedPrompt"
                  @click="handleCopy(promptPackage?.combinedPrompt, '完整任务 Prompt')"
                >
                  📋 一键复制任务 Prompt
                </el-button>
              </div>
            </div>

            <div v-loading="isExportingPackage" class="relative">
              <el-input
                :model-value="promptPackage?.combinedPrompt || ''"
                type="textarea"
                :rows="8"
                readonly
                class="font-mono text-xs"
                placeholder="正在构建造型提示词任务包..."
              />
            </div>
          </div>

          <!-- 子面板 2: 粘贴外部 AI 结果与解析 -->
          <div v-if="activeManualTab === 'paste'" class="p-3 space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-xs text-slate-600">
                将 ChatGPT / Claude / Gemini 返回的 JSON 文本粘贴到下方：
              </span>
              <el-button
                type="primary"
                size="small"
                :loading="isParsingResult"
                :disabled="!rawManualResponse.trim()"
                @click="handleParseManualResponse"
              >
                🔍 解析并载入预览
              </el-button>
            </div>

            <el-input
              v-model="rawManualResponse"
              type="textarea"
              :rows="7"
              class="font-mono text-xs"
              placeholder="在此粘贴外部 AI 返回的包含 outfitPrompt、negativePrompt 的 JSON 内容..."
            />

            <!-- 校验反馈报告 -->
            <div v-if="validationInfo" class="text-xs rounded-lg p-2.5 space-y-1" :class="validationInfo.errors.length > 0 ? 'bg-rose-50 border border-rose-200 text-rose-800' : 'bg-emerald-50 border border-emerald-200 text-emerald-800'">
              <div class="font-bold flex items-center gap-1">
                <span>{{ validationInfo.errors.length > 0 ? '❌ 校验未通过' : '✓ 校验通过' }}</span>
              </div>
              <div v-for="(err, idx) in validationInfo.errors" :key="'e-'+idx" class="text-rose-600">
                • {{ err }}
              </div>
              <div v-for="(warn, idx) in validationInfo.warnings" :key="'w-'+idx" class="text-amber-600">
                • 提示: {{ warn }}
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 3. 生成/解析结果预览与微调确认区 -->
      <div v-if="previewResult" class="space-y-3 pt-2 border-t border-slate-200">
        <div class="flex items-center justify-between">
          <span class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
            <span>✨</span> 造型专属 Prompt 预览与确认 (可在此直接微调)
          </span>
          <el-button
            size="small"
            link
            type="primary"
            @click="handleCopy(previewResult.outfitPrompt, '造型提示词')"
          >
            复制造型词
          </el-button>
        </div>

        <div class="space-y-2.5">
          <!-- outfitPrompt -->
          <div>
            <div class="flex items-center justify-between mb-1">
              <label class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                <span>👗</span> 造型/服装专属提示词 (outfitPrompt)
              </label>
              <span class="text-[11px] text-slate-400">聚焦服装款式、面料材质、配色版型、随身配饰等</span>
            </div>
            <el-input
              v-model="previewResult.outfitPrompt"
              type="textarea"
              :rows="4"
              class="font-mono text-xs"
              placeholder="造型服饰提示词..."
            />
          </div>

          <!-- appearancePrompt 融合词 (可选) -->
          <div v-if="previewResult.appearancePrompt">
            <div class="flex items-center justify-between mb-1">
              <label class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                <span>👤</span> 完整外观融合提示词 (appearancePrompt)
              </label>
              <span class="text-[11px] text-slate-400">稳定身份特征 + 当前造型的整体英文 Prompt</span>
            </div>
            <el-input
              v-model="previewResult.appearancePrompt"
              type="textarea"
              :rows="3"
              class="font-mono text-xs"
              placeholder="外观融合提示词..."
            />
          </div>

          <!-- negativePrompt 专属负向词 -->
          <div>
            <div class="flex items-center justify-between mb-1">
              <label class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                <span>🚫</span> 造型专属负向提示词 (negativePrompt)
              </label>
              <span class="text-[11px] text-slate-400">排除不合身、形变、违和现代饰品、破损等</span>
            </div>
            <el-input
              v-model="previewResult.negativePrompt"
              type="textarea"
              :rows="2"
              class="font-mono text-xs"
              placeholder="负向提示词..."
            />
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-between">
        <div class="text-xs text-slate-400">
          <span v-if="isStreaming" class="text-emerald-600 flex items-center gap-1 font-mono">
            <span class="inline-block w-2 h-2 rounded-full bg-emerald-500 animate-ping"></span>
            流式生成中，请稍候...
          </span>
          <span v-else-if="previewResult" class="text-slate-500">
            满意请点击「确认采纳并回填到造型」更新当前造型表单
          </span>
        </div>

        <div class="flex gap-2">
          <el-button v-if="isStreaming" type="primary" plain @click="handleMoveToBackground">收起并继续生成</el-button>
          <el-button :disabled="isStreaming" @click="handleCancel">取消</el-button>

          <!-- API 模式生成按钮 -->
          <template v-if="executionMode === 'API'">
            <el-button
              v-if="!isStreaming && !previewResult"
              type="primary"
              @click="handleStartStream"
            >
              ⚡ 开始生成
            </el-button>
            <el-button
              v-if="previewResult && !isStreaming"
              plain
              type="warning"
              @click="handleStartStream"
            >
              🔄 重新生成
            </el-button>
          </template>

          <!-- MANUAL 模式操作 -->
          <template v-else>
            <el-button
              v-if="activeManualTab === 'prompt'"
              type="primary"
              :disabled="!promptPackage?.combinedPrompt"
              @click="handleCopy(promptPackage?.combinedPrompt, '完整任务 Prompt')"
            >
              📋 复制任务 Prompt
            </el-button>
          </template>

          <!-- 确认回填按钮 -->
          <el-button
            v-if="previewResult"
            type="success"
            :disabled="isStreaming"
            @click="handleConfirmApply"
          >
            ✓ 确认采纳并回填到造型
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, nextTick, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { characterApi } from '@/api/res-character'
import { aiProviderApi } from '@/api/ai-provider'
import DictSelect from '@/components/DictSelect.vue'
import SkillSelector from '@/components/SkillSelector.vue'
import type {
  OutfitPromptDeriveDTO,
  OutfitPromptDeriveVO,
  ResCharacter,
  ResCharacterLook,
  ResCharacterOutfit,
  AssetPromptPackageVO
} from '@/types/resource'
import type { AiProviderVO, AiModel } from '@/types/ai-provider'
import type { Drama } from '@/types/drama'

const emit = defineEmits<{
  (e: 'apply', result: OutfitPromptDeriveVO): void
  (e: 'task-starting'): void
  (e: 'task-finished'): void
  (e: 'panel-close'): void
}>()

const visible = ref(false)
const executionMode = ref<'API' | 'MANUAL'>('API')
const isStreaming = ref(false)
const streamText = ref('')
const elapsedSeconds = ref(0)
const stylePreset = ref('')
const styleTone = ref('')
const skillNames = ref<string[]>([])
const dramaInfo = ref<Drama | null>(null)
const providerId = ref<string | undefined>()
const modelCode = ref<string | undefined>()

// MANUAL 模式相关
const activeManualTab = ref<'prompt' | 'paste'>('prompt')
const promptPackage = ref<AssetPromptPackageVO | null>(null)
const isExportingPackage = ref(false)
const rawManualResponse = ref('')
const isParsingResult = ref(false)
const validationInfo = ref<{
  errors: string[]
  warnings: string[]
} | null>(null)

const providers = ref<AiProviderVO[]>([])
const models = ref<AiModel[]>([])

const currentCharacter = reactive<Partial<ResCharacter>>({})
const currentLook = reactive<Partial<ResCharacterLook & ResCharacterOutfit>>({})
const previewResult = ref<OutfitPromptDeriveVO | null>(null)
const streamConsoleRef = ref<HTMLPreElement | null>(null)

let timer: any = null
let abortController: AbortController | null = null

const modalTitle = computed(() => {
  const lookName = currentLook.lookName || '造型'
  return `⚡ AI 智能生成造型提示词 - ${lookName}`
})

function open(
  look: Partial<ResCharacterLook & ResCharacterOutfit>,
  character: Partial<ResCharacter>,
  drama?: Drama | null
) {
  Object.assign(currentCharacter, character)
  Object.assign(currentLook, look)

  dramaInfo.value = drama || null
  stylePreset.value = character.dramaId ? (drama?.stylePreset || '') : ''
  skillNames.value = []

  previewResult.value = null
  streamText.value = ''
  rawManualResponse.value = ''
  validationInfo.value = null
  activeManualTab.value = 'prompt'
  visible.value = true

  loadProviders()
  if (executionMode.value === 'MANUAL') {
    loadPromptPackage()
  }
}

function reopen() {
  visible.value = true
}

function handleExecutionModeChange(mode: string | number | boolean | undefined) {
  if (mode === 'MANUAL' && !promptPackage.value) {
    loadPromptPackage()
  }
}

function handleSkillChange() {
  if (executionMode.value === 'MANUAL' && visible.value) {
    loadPromptPackage()
  }
}

function onStyleParamsChange() {
  if (executionMode.value === 'MANUAL') {
    loadPromptPackage()
  }
}

function buildDerivePayload(): OutfitPromptDeriveDTO {
  return {
    lookId: currentLook.id ? String(currentLook.id) : undefined,
    characterId: currentCharacter.id ? String(currentCharacter.id) : undefined,
    outfitName: currentLook.lookName,
    lookType: currentLook.lookType,
    designDesc: currentLook.designDesc,
    characterName: currentCharacter.name,
    characterGender: currentCharacter.gender,
    characterAppearanceDesc: currentCharacter.appearanceDesc,
    dramaId: currentCharacter.dramaId ? String(currentCharacter.dramaId) : undefined,
    stylePreset: stylePreset.value.trim() || undefined,
    styleTone: styleTone.value.trim() || undefined,
    providerId: providerId.value,
    modelCode: modelCode.value,
    requiredSkillNames: executionMode.value === 'API' && skillNames.value.length > 0 ? skillNames.value : undefined,
    selectedSkillNames: executionMode.value === 'MANUAL' && skillNames.value.length > 0 ? skillNames.value : undefined
  }
}

async function loadPromptPackage() {
  if (!currentLook.designDesc?.trim()) {
    return
  }
  isExportingPackage.value = true
  try {
    const payload = buildDerivePayload()
    const res = await characterApi.deriveOutfitPromptPackage(payload)
    promptPackage.value = res
  } catch (err: any) {
    ElMessage.error(err.message || '生成造型任务 Prompt 失败')
  } finally {
    isExportingPackage.value = false
  }
}

function handleParseManualResponse() {
  if (!rawManualResponse.value.trim()) {
    ElMessage.warning('请先粘贴外部 AI 返回的 JSON 文本')
    return
  }

  isParsingResult.value = true
  validationInfo.value = null

  try {
    let cleanText = rawManualResponse.value.trim()
    const jsonMatch = cleanText.match(/```(?:json)?\s*([\s\S]*?)\s*```/)
    if (jsonMatch && jsonMatch[1]) {
      cleanText = jsonMatch[1].trim()
    } else {
      const firstBrace = cleanText.indexOf('{')
      const lastBrace = cleanText.lastIndexOf('}')
      if (firstBrace >= 0 && lastBrace > firstBrace) {
        cleanText = cleanText.substring(firstBrace, lastBrace + 1)
      }
    }

    const parsed = JSON.parse(cleanText)
    const errors: string[] = []
    const warnings: string[] = []

    const outfitPrompt = parsed.outfitPrompt || parsed.outfit_prompt || parsed.appearancePrompt || parsed.appearance_prompt
    if (!outfitPrompt) {
      errors.push('缺少核心造型服饰提示词 outfitPrompt (或 outfit_prompt)')
    }

    validationInfo.value = { errors, warnings }

    if (errors.length > 0) {
      ElMessage.error(`解析发现 ${errors.length} 项错误，请核对后重试`)
      return
    }

    previewResult.value = {
      outfitPrompt: (parsed.outfitPrompt || parsed.outfit_prompt || '').trim(),
      appearancePrompt: (parsed.appearancePrompt || parsed.appearance_prompt || '').trim() || undefined,
      negativePrompt: (parsed.negativePrompt || parsed.negative_prompt || '').trim() || undefined
    }
    ElMessage.success('✓ 外部 AI 结果解析成功！已载入下方预览')
  } catch (err: any) {
    ElMessage.error(err.message || 'JSON 解析失败，请检查格式是否规范')
  } finally {
    isParsingResult.value = false
  }
}

function handleViewJsonExample() {
  const example = `{
  "outfitPrompt": "wearing a dark navy tailored trench coat, white silk shirt underneath with loose collar, charcoal slim trousers, leather Chelsea boots, subtle silver pocket watch chain",
  "appearancePrompt": "anime style, 1boy, handsome young swordsman, wearing a dark navy tailored trench coat, white silk shirt underneath with loose collar, charcoal slim trousers",
  "negativePrompt": "t-shirt, casual sportswear, sneakers, bright neon colors, torn fabric, bad anatomy"
}`
  ElMessageBox.alert(
    `<pre class="bg-slate-900 text-emerald-300 p-3 rounded text-xs font-mono overflow-auto max-h-60">${example}</pre>`,
    '造型专属 Prompt 输出 JSON 结构规范示例',
    {
      dangerouslyUseHTMLString: true,
      confirmButtonText: '我知道了'
    }
  )
}

async function handleCopy(text?: string, title = 'Prompt') {
  if (!text) {
    ElMessage.warning('内容为空，无法复制')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`✓ ${title} 已成功复制到剪贴板！`)
  } catch (e) {
    const input = document.createElement('textarea')
    input.value = text
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
    ElMessage.success(`✓ ${title} 已复制到剪贴板！`)
  }
}

async function loadProviders() {
  try {
    const list = await aiProviderApi.getListEnabled()
    providers.value = list || []
    if (providers.value.length > 0 && !providerId.value) {
      providerId.value = String(providers.value[0].id)
      await handleProviderChange(providerId.value)
    }
  } catch (e) {
    console.error('加载提供商失败', e)
  }
}

async function handleProviderChange(pId?: string | number) {
  modelCode.value = undefined
  models.value = []
  if (!pId) return
  try {
    const mList = await aiProviderApi.getModelList(pId, 'CHAT')
    models.value = (mList || []).filter(m => m.status === 1)
    if (models.value.length > 0) {
      modelCode.value = models.value[0].modelCode
    }
  } catch (e) {
    console.error('加载模型列表失败', e)
  }
}

async function handleStartStream() {
  if (!currentLook.designDesc?.trim()) {
    ElMessage.warning('请先填写造型视觉概念描述')
    return
  }

  isStreaming.value = true
  emit('task-starting')
  streamText.value = ''
  elapsedSeconds.value = 0
  previewResult.value = null

  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    elapsedSeconds.value++
  }, 1000)

  abortController = new AbortController()

  const payload = buildDerivePayload()

  try {
    await characterApi.deriveOutfitPromptStream(payload, {
      signal: abortController.signal,
      onChunk(chunk) {
        streamText.value += chunk
        nextTick(() => {
          if (streamConsoleRef.value) {
            streamConsoleRef.value.scrollTop = streamConsoleRef.value.scrollHeight
          }
        })
      },
      onResult(res) {
        previewResult.value = res
        isStreaming.value = false
        emit('task-finished')
        if (timer) {
          clearInterval(timer)
          timer = null
        }
        ElMessage.success('造型专属 Prompt 衍生成功！请在下方预览确认')
      },
      onError(err) {
        isStreaming.value = false
        emit('task-finished')
        if (timer) {
          clearInterval(timer)
          timer = null
        }
        ElMessage.error(err.message || 'AI 智能衍生造型提示词失败')
      },
      onDone() {
        if (isStreaming.value) {
          isStreaming.value = false
          emit('task-finished')
        }
        if (timer) {
          clearInterval(timer)
          timer = null
        }
      }
    })
  } catch (err: any) {
    if (isStreaming.value) {
      isStreaming.value = false
      emit('task-finished')
      if (timer) {
        clearInterval(timer)
        timer = null
      }
    }
    if (err?.name !== 'AbortError') ElMessage.error(err?.message || 'AI 智能衍生造型提示词失败')
  }
}

function handleAbortStream() {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  isStreaming.value = false
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  ElMessage.info('已停止 AI 流式生成')
}

function handleConfirmApply() {
  if (!previewResult.value) return
  emit('apply', previewResult.value)
  visible.value = false
  emit('panel-close')
  ElMessage.success('已采纳并回填到造型表单！')
}

function handleCancel() {
  if (isStreaming.value) {
    handleAbortStream()
  }
  visible.value = false
  emit('panel-close')
}

function handleMoveToBackground() {
  visible.value = false
}

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (abortController) abortController.abort()
})

defineExpose({
  open,
  reopen
})
</script>

<style scoped>
:deep(.look-prompt-derive-dialog) {
  border-radius: 12px;
  overflow: hidden;
}
:deep(.look-prompt-derive-dialog .el-dialog__header) {
  margin-right: 0;
  padding: 16px 20px;
  border-bottom: 1px solid #e2e8f0;
}
:deep(.look-prompt-derive-dialog .el-dialog__body) {
  padding: 16px 20px;
}
:deep(.look-prompt-derive-dialog .el-dialog__footer) {
  padding: 12px 20px;
  border-top: 1px solid #e2e8f0;
  background-color: #f8fafc;
}
</style>
