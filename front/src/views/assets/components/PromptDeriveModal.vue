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
    class="prompt-derive-dialog"
  >
    <div class="space-y-4 max-h-[72vh] overflow-y-auto pr-1">
      <!-- 1. 当前输入上下文横幅与补充设置 -->
      <div class="bg-slate-50 border border-slate-200 rounded-xl p-3.5 space-y-3">
        <div class="flex items-center justify-between">
          <span class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
            <span>🎭</span> 当前角色基础人设上下文 (稳定身份层)
          </span>
          <el-tag size="small" type="primary" effect="plain">
            {{ currentContext.name || '未命名' }} · {{ currentContext.gender || 'UNKNOWN' }} · {{ currentContext.roleType || 'PROTAGONIST' }}
          </el-tag>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-3 gap-2 text-xs">
          <div class="bg-white p-2 rounded border border-slate-100">
            <span class="text-slate-400 block mb-0.5">姓名与年龄段</span>
            <span class="font-medium text-slate-800">{{ currentContext.name || '未命名' }} ({{ currentContext.ageGroup || 'YOUTH' }})</span>
          </div>
          <div class="bg-white p-2 rounded border border-slate-100 md:col-span-2">
            <span class="text-slate-400 block mb-0.5">内在性格灵魂 (内在心智)</span>
            <span class="font-medium text-slate-800 line-clamp-1">{{ currentContext.personality || '暂未填写' }}</span>
          </div>
          <div class="bg-white p-2 rounded border border-slate-100 md:col-span-3">
            <span class="text-slate-400 block mb-0.5">中文外貌描述 (原著视觉 SSOT)</span>
            <span class="font-medium text-slate-800 line-clamp-2">{{ currentContext.appearanceDesc || '暂未填写' }}</span>
          </div>
        </div>

        <!-- 关联短剧自动带入状态提示 -->
        <div v-if="dramaInfo" class="flex items-center justify-between bg-indigo-50/80 border border-indigo-200/80 rounded-lg px-3 py-2 text-xs text-indigo-900">
          <div class="flex items-center gap-1.5 font-medium">
            <span>🎬</span>
            <span>关联短剧：《{{ dramaInfo.title }}》</span>
            <span class="text-indigo-300">|</span>
            <span class="text-indigo-600 font-normal">已自动带入短剧全局风格与视觉基调指南</span>
          </div>
          <el-tag size="small" type="success" effect="plain" class="!font-medium">自动继承中</el-tag>
        </div>

        <!-- 补充画风指导与大模型选择 -->
        <SkillSelector
          v-model="skillNames"
          :mode="executionMode"
          :disabled="isStreaming || isExportingPackage"
          @update:model-value="handleSkillChange"
        />
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
              {{ executionMode === 'API' ? '使用系统中配置的模型密钥流式生成' : '导出任务 Prompt 粘贴至 ChatGPT / Claude / Gemini，零 API 费用' }}
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

            <!-- API 模式展示模型选择，MANUAL 模式展示无需 API 提示 -->
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
                    :key="m.id"
                    :label="m.modelName"
                    :value="m.modelCode"
                  />
                </el-select>
              </div>
            </div>
            <div v-else class="flex flex-col justify-center bg-emerald-50/70 border border-emerald-200/80 rounded px-2.5 py-1.5 text-xs text-emerald-900">
              <div class="font-semibold flex items-center gap-1 text-[11px]">
                <span>✓</span> 外部 AI 手工模式已就绪
              </div>
              <div class="text-[10px] text-emerald-700">
                零 API 费用。系统直接生成完整 Prompt，支持 ChatGPT / Claude / Gemini。
              </div>
            </div>
          </div>

          <div>
            <label class="block text-[11px] font-semibold text-slate-700 mb-1">
              🎥 视觉风格基调 / 导演指南 (styleTone)
              <span class="text-[10px] text-slate-400 font-normal ml-1">自动继承短剧设定，光影/色调/镜头质感深度融入</span>
            </label>
            <el-input
              v-model="styleTone"
              type="textarea"
              :rows="2"
              placeholder="如：冷调蓝灰光影、微弱轮廓逆光、35mm胶片颗粒质感、好莱坞大片暗调氛围（自动带入短剧配置）"
              size="small"
              clearable
              :disabled="isStreaming"
              @change="onStyleParamsChange"
            />
          </div>
        </div>
      </div>

      <!-- 2. 外部 AI 手工生成工作区 (MANUAL 模式专属) -->
      <div v-if="executionMode === 'MANUAL'" class="space-y-3">
        <!-- 步骤提示导航条 -->
        <div class="flex items-center justify-between bg-sky-50/90 border border-sky-200 rounded-xl px-3.5 py-2 text-xs text-sky-950">
          <div class="flex items-center gap-2 sm:gap-4 flex-wrap">
            <span class="font-bold flex items-center gap-1">
              <span class="w-4 h-4 rounded-full bg-sky-600 text-white flex items-center justify-center text-[10px]">1</span>
              生成并复制完整 Prompt
            </span>
            <span class="text-sky-300">→</span>
            <span class="font-bold flex items-center gap-1">
              <span class="w-4 h-4 rounded-full bg-sky-600 text-white flex items-center justify-center text-[10px]">2</span>
              粘贴到外部 AI 获取 JSON
            </span>
            <span class="text-sky-300">→</span>
            <span class="font-bold flex items-center gap-1">
              <span class="w-4 h-4 rounded-full bg-sky-600 text-white flex items-center justify-center text-[10px]">3</span>
              粘贴结果并校验回填
            </span>
          </div>
          <el-button
            size="small"
            type="primary"
            plain
            :loading="isExportingPackage"
            @click="loadPromptPackage"
          >
            🔄 刷新任务 Prompt
          </el-button>
        </div>

        <!-- 任务工作区页签 (Prompt 导出 / 结果粘贴) -->
        <el-tabs v-model="activeManualTab" type="border-card" class="rounded-xl overflow-hidden shadow-xs">
          <!-- 页签 1: 任务 Prompt -->
          <el-tab-pane label="📋 任务 Prompt" name="prompt">
            <div class="space-y-2.5">
              <div class="flex items-center justify-between text-xs text-slate-500 flex-wrap gap-2">
                <div class="flex items-center gap-2">
                  <span>版本: <el-tag size="small" type="info">{{ promptPackage?.templateVersion || 'character-identity-prompt-v2' }}</el-tag></span>
                  <span>指纹: <code class="text-[10px] bg-slate-100 px-1 py-0.5 rounded text-slate-600 font-mono">{{ promptPackage?.contextFingerprint || '计算中...' }}</code></span>
                  <span>字符数: <strong class="text-slate-700">{{ promptPackage?.combinedPrompt?.length || 0 }}</strong> 字</span>
                </div>
                <div class="flex gap-1.5">
                  <el-button
                    size="small"
                    type="primary"
                    :disabled="!promptPackage?.combinedPrompt"
                    @click="handleCopy(promptPackage?.combinedPrompt, '完整任务 Prompt')"
                  >
                    📋 复制完整 Prompt (推荐)
                  </el-button>
                  <el-button
                    size="small"
                    plain
                    :disabled="!promptPackage?.systemPrompt"
                    @click="handleCopy(promptPackage?.systemPrompt, 'System Prompt')"
                  >
                    复制 System
                  </el-button>
                  <el-button
                    size="small"
                    plain
                    :disabled="!promptPackage?.userPrompt"
                    @click="handleCopy(promptPackage?.userPrompt, 'User Prompt')"
                  >
                    复制 User
                  </el-button>
                </div>
              </div>

              <div>
                <el-input
                  :model-value="promptPackage?.combinedPrompt || '正在生成任务 Prompt 包...'"
                  type="textarea"
                  :rows="8"
                  readonly
                  class="font-mono text-xs select-all"
                  placeholder="正在生成任务 Prompt 包..."
                />
              </div>
              <div class="flex items-center justify-between text-[11px] text-slate-400">
                <span>💡 提示：点击「复制完整 Prompt」后，直接在外部 AI 聊天窗口粘贴发送。获取到 JSON 后切换到下一页签。</span>
                <el-button size="small" link type="primary" @click="activeManualTab = 'paste'">
                  去粘贴结果 →
                </el-button>
              </div>
            </div>
          </el-tab-pane>

          <!-- 页签 2: 粘贴外部 AI 结果 -->
          <el-tab-pane label="📥 粘贴外部 AI 结果" name="paste">
            <div class="space-y-3">
              <div>
                <div class="flex items-center justify-between mb-1">
                  <label class="text-xs font-semibold text-slate-700">
                    请粘贴外部 AI 返回的完整内容 (支持包含 ```json 代码块或说明文字)：
                  </label>
                  <div class="flex gap-2">
                    <el-button size="small" link type="primary" @click="handleViewJsonExample">
                      查看输出格式示例
                    </el-button>
                    <el-button size="small" link type="danger" @click="rawManualResponse = ''">
                      清空
                    </el-button>
                  </div>
                </div>
                <el-input
                  v-model="rawManualResponse"
                  type="textarea"
                  :rows="7"
                  class="font-mono text-xs"
                  placeholder="请在此粘贴外部 AI 返回的 JSON 内容..."
                />
              </div>

              <!-- 校验诊断警告/错误横幅 -->
              <div v-if="validationInfo" class="space-y-1.5">
                <el-alert
                  v-if="validationInfo.errors.length > 0"
                  type="error"
                  show-icon
                  :closable="false"
                  :title="`解析校验发现 ${validationInfo.errors.length} 项硬性错误：`"
                >
                  <ul class="list-disc pl-4 text-xs mt-1 space-y-0.5">
                    <li v-for="(err, idx) in validationInfo.errors" :key="idx">{{ err }}</li>
                  </ul>
                </el-alert>
                <el-alert
                  v-if="validationInfo.warnings.length > 0"
                  type="warning"
                  show-icon
                  :closable="false"
                  :title="`合规性与建议警告 (${validationInfo.warnings.length} 项)：`"
                >
                  <ul class="list-disc pl-4 text-xs mt-1 space-y-0.5">
                    <li v-for="(warn, idx) in validationInfo.warnings" :key="idx">{{ warn }}</li>
                  </ul>
                </el-alert>
              </div>

              <div class="flex justify-end gap-2">
                <el-button
                  type="primary"
                  :loading="isParsingResult"
                  :disabled="!rawManualResponse.trim()"
                  @click="handleParseManualResponse"
                >
                  ⚡ 解析并预览
                </el-button>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>

      <!-- 3. 流式输出动态控制台 (打字机效果，仅 API 模式展示) -->
      <div v-if="executionMode === 'API' && (isStreaming || streamText)" class="rounded-xl border border-slate-800 bg-slate-900 text-slate-200 p-3.5 shadow-inner">
        <div class="flex items-center justify-between pb-2 mb-2 border-b border-slate-700/60 text-xs">
          <div class="flex items-center gap-2">
            <span class="inline-block w-2 h-2 rounded-full" :class="isStreaming ? 'bg-emerald-400 animate-pulse' : 'bg-blue-400'"></span>
            <span class="font-mono font-medium">
              {{ isStreaming ? '⚡ AI 实时构思与流式解析中...' : '✓ AI 构思生成完毕' }}
            </span>
          </div>
          <span class="text-[11px] text-slate-400 font-mono">已耗时 {{ elapsedSeconds }}s</span>
        </div>
        <pre
          ref="streamConsoleRef"
          class="font-mono text-xs whitespace-pre-wrap leading-relaxed max-h-44 overflow-y-auto text-emerald-300 select-text"
        >{{ streamText || '等待大模型响应片元...' }}</pre>
      </div>

      <!-- 4. 结构化结果预览卡片 (Preview Panels) -->
      <div v-if="previewResult" class="space-y-3 pt-1">
        <div class="flex items-center justify-between">
          <span class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
            <span>✨</span> 稳定身份 Prompt 预览 (确认无误后可一键采纳回填)
          </span>
          <el-tag size="small" type="success" effect="dark">已生成完成</el-tag>
        </div>

        <div class="p-4 rounded-xl border border-slate-200 bg-white shadow-xs space-y-3">
          <div>
            <div class="flex items-center justify-between mb-1">
              <label class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                <span>👤</span> 稳定身份外貌 Prompt (appearancePrompt)
              </label>
              <span class="text-[11px] text-slate-400">仅包含发型发色、五官骨相、身材体型等跨服装稳定生物特征</span>
            </div>
            <el-input
              v-model="previewResult.appearancePrompt"
              type="textarea"
              :rows="4"
              class="font-mono text-xs"
              placeholder="外观提示词..."
            />
          </div>

          <div>
            <div class="flex items-center justify-between mb-1">
              <label class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                <span>🚫</span> 身份级专属负向 Prompt (negativePrompt)
              </label>
              <span class="text-[11px] text-slate-400">排除畸形、非对应性别体态、不合规画风媒介词等</span>
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
            流式传输中，请稍候...
          </span>
          <span v-else-if="previewResult" class="text-slate-500">
            满意请点击右侧「确认采纳并回填」更新角色表单
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

          <!-- MANUAL 模式复制/去粘贴按钮 -->
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

          <!-- 确认回填按钮 (通用) -->
          <el-button
            v-if="previewResult"
            type="success"
            :disabled="isStreaming"
            @click="handleConfirmApply"
          >
            ✓ 确认采纳并回填
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
import { dramaApi } from '@/api/drama'
import SkillSelector from '@/components/SkillSelector.vue'
import type {
  CharacterVisualPromptDeriveDTO,
  CharacterVisualPromptDeriveVO,
  ResCharacter,
  AssetPromptPackageVO
} from '@/types/resource'
import type { AiProviderVO, AiModel } from '@/types/ai-provider'
import type { Drama } from '@/types/drama'

const emit = defineEmits<{
  (e: 'apply', result: CharacterVisualPromptDeriveVO): void
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

// MANUAL 模式相关状态
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

const currentContext = reactive<Partial<ResCharacter>>({})
const previewResult = ref<CharacterVisualPromptDeriveVO | null>(null)
const streamConsoleRef = ref<HTMLPreElement | null>(null)

let timer: ReturnType<typeof setInterval> | null = null
let abortController: AbortController | null = null
let panelCloseEmitted = false

const modalTitle = computed(() => {
  return executionMode.value === 'API'
    ? '⚡ AI 依据人设生成 Prompt (API 模式)'
    : '📋 外部 AI 手工生成 Prompt (零 API 费用)'
})

function open(character: Partial<ResCharacter>) {
  panelCloseEmitted = false
  Object.assign(currentContext, character)
  stylePreset.value = ''
  styleTone.value = ''
  skillNames.value = []
  dramaInfo.value = null
  streamText.value = ''
  previewResult.value = null
  elapsedSeconds.value = 0
  activeManualTab.value = 'prompt'
  rawManualResponse.value = ''
  validationInfo.value = null
  promptPackage.value = null
  visible.value = true

  if (character.dramaId && character.dramaId !== 0 && character.dramaId !== '0') {
    dramaApi.getById(character.dramaId).then(res => {
      dramaInfo.value = res
      if (res) {
        if (res.stylePreset) {
          stylePreset.value = res.stylePreset
        }
        if (res.styleTone) {
          styleTone.value = res.styleTone
        }
      }
      if (executionMode.value === 'MANUAL') {
        loadPromptPackage()
      }
    }).catch(err => {
      console.warn('获取关联短剧详情失败:', err)
      if (executionMode.value === 'MANUAL') {
        loadPromptPackage()
      }
    })
  } else {
    if (executionMode.value === 'MANUAL') {
      loadPromptPackage()
    }
  }

  loadProviders()
}

function handleExecutionModeChange() {
  if (executionMode.value === 'MANUAL') {
    if (!promptPackage.value) {
      loadPromptPackage()
    }
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

function buildDerivePayload(): CharacterVisualPromptDeriveDTO {
  return {
    characterId: currentContext.id,
    dramaId: currentContext.dramaId,
    name: currentContext.name,
    gender: currentContext.gender,
    ageGroup: currentContext.ageGroup,
    roleType: currentContext.roleType,
    personality: currentContext.personality,
    appearanceDesc: currentContext.appearanceDesc,
    stylePreset: stylePreset.value.trim() || undefined,
    styleTone: styleTone.value.trim() || undefined,
    visualStyle: stylePreset.value.trim() || undefined,
    providerId: providerId.value,
    modelCode: modelCode.value,
    requiredSkillNames: executionMode.value === 'API' && skillNames.value.length > 0 ? skillNames.value : undefined,
    selectedSkillNames: executionMode.value === 'MANUAL' && skillNames.value.length > 0 ? skillNames.value : undefined
  }
}

async function loadPromptPackage() {
  if (!currentContext.name && !currentContext.appearanceDesc && !currentContext.personality) {
    return
  }
  isExportingPackage.value = true
  try {
    const payload = buildDerivePayload()
    const res = await characterApi.deriveVisualPromptPackage(payload)
    promptPackage.value = res
  } catch (err: any) {
    ElMessage.error(err.message || '生成任务 Prompt 失败')
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

    if (!parsed.appearancePrompt && !parsed.appearance_prompt) {
      errors.push('缺少核心外观字段 appearancePrompt')
    }

    validationInfo.value = { errors, warnings }

    if (errors.length > 0) {
      ElMessage.error(`解析发现 ${errors.length} 项错误，请核对后重试`)
      return
    }

    previewResult.value = {
      appearancePrompt: (parsed.appearancePrompt || parsed.appearance_prompt || '').trim(),
      negativePrompt: (parsed.negativePrompt || parsed.negative_prompt || '').trim()
    }
    ElMessage.success('✓ 外部 AI 结果解析成功！已加载至下方预览')
  } catch (err: any) {
    ElMessage.error(err.message || 'JSON 解析失败，请检查格式是否规范')
  } finally {
    isParsingResult.value = false
  }
}

function handleViewJsonExample() {
  const example = `{
  "appearancePrompt": "anime style, 1boy, handsome young swordsman, raven black hair tied in high ponytail, sharp phoenix eyes, cool confident expression, athletic lean build",
  "negativePrompt": "photorealistic, thick black outline, bad anatomy, deformed, mutated hands, multiple views, eyeglasses"
}`
  ElMessageBox.alert(
    `<pre class="bg-slate-900 text-emerald-300 p-3 rounded text-xs font-mono overflow-auto max-h-60">${example}</pre>`,
    '角色稳定身份 Prompt 输出 JSON 结构规范示例',
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
  if (!currentContext.name && !currentContext.appearanceDesc && !currentContext.personality) {
    ElMessage.warning('请确保至少填写了角色名称、性格或中文外貌描述')
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
    await characterApi.deriveVisualPromptStream(payload, {
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
        ElMessage.success('稳定身份 Prompt 衍生成功！请在下方预览确认')
      },
      onError(err) {
        isStreaming.value = false
        emit('task-finished')
        if (timer) {
          clearInterval(timer)
          timer = null
        }
        ElMessage.error(err.message || 'AI 智能衍生提示词失败')
      },
      onDone() {
        isStreaming.value = false
        emit('task-finished')
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
    if (err?.name !== 'AbortError') ElMessage.error(err?.message || 'AI 智能衍生提示词失败')
  }
}

function handleConfirmApply() {
  if (!previewResult.value) return
  emit('apply', previewResult.value)
  visible.value = false
  closePanel()
  ElMessage.success('已成功采纳并回填角色身份 Prompt！')
}

function handleCancel() {
  if (isStreaming.value && abortController) {
    abortController.abort()
  }
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  isStreaming.value = false
  visible.value = false
  emit('task-finished')
  closePanel()
}

function handleMoveToBackground() {
  visible.value = false
}

function reopen() {
  visible.value = true
}

function closePanel() {
  if (panelCloseEmitted) return
  panelCloseEmitted = true
  emit('panel-close')
}

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (abortController) abortController.abort()
})

defineExpose({ open, reopen })
</script>

<style scoped>
:deep(.prompt-derive-dialog .el-dialog__body) {
  padding: 16px 20px;
}
</style>
