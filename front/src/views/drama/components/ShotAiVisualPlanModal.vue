<template>
  <el-dialog
    v-model="visible"
    title="✨ AI 分镜视觉方案"
    width="820px"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
  >
    <div v-loading="loading" class="flex flex-col gap-4 max-h-[72vh] overflow-y-auto px-1">
      <div class="bg-slate-50 border border-slate-200 rounded-lg p-3 text-xs text-slate-600">
        AI 将只根据当前分镜已绑定的人物、场景、道具和镜头参数生成方案，不会修改资产库数据。
      </div>

      <el-form label-position="top">
        <el-form-item label="创作者补充要求">
          <el-input v-model="instruction" type="textarea" :rows="2" placeholder="可选，例如：突出人物压迫感，保持人物位于画面左侧" />
        </el-form-item>
      </el-form>

      <template v-if="plan">
        <div class="grid grid-cols-1 gap-3">
          <section class="rounded-lg border border-indigo-200 bg-indigo-50/50 p-3">
            <div class="flex items-center justify-between mb-2">
              <span class="text-sm font-bold text-indigo-950">🖼️ 首帧生图 Prompt</span>
              <el-button size="small" link type="primary" @click="copy(plan.firstFramePrompt)">复制</el-button>
            </div>
            <el-input v-model="plan.firstFramePrompt" type="textarea" :rows="5" />
          </section>

          <section class="rounded-lg border border-emerald-200 bg-emerald-50/50 p-3">
            <div class="flex items-center justify-between mb-2">
              <span class="text-sm font-bold text-emerald-950">🎥 视频运镜 Prompt</span>
              <el-button size="small" link type="success" @click="copy(plan.videoPrompt)">复制</el-button>
            </div>
            <el-input v-model="plan.videoPrompt" type="textarea" :rows="4" placeholder="AI 未返回运镜提示词" />
          </section>

          <section class="rounded-lg border border-rose-200 bg-rose-50/50 p-3">
            <div class="flex items-center justify-between mb-2">
              <span class="text-sm font-bold text-rose-950">🚫 负向 Prompt</span>
              <el-button size="small" link type="danger" @click="copy(plan.negativePrompt)">复制</el-button>
            </div>
            <el-input v-model="plan.negativePrompt" type="textarea" :rows="3" placeholder="AI 未返回负向提示词" />
          </section>
        </div>

        <div v-if="plan.controlImages?.length" class="rounded-lg border border-slate-200 p-3">
          <div class="text-xs font-bold text-slate-700 mb-2">参考图（{{ plan.controlImages.length }}）</div>
          <div class="grid grid-cols-3 gap-2">
            <div v-for="image in plan.controlImages" :key="`${image.controlType}-${image.imageUrl}`" class="text-xs text-slate-600">
              <el-image :src="image.imageUrl" fit="cover" class="w-full h-24 rounded border" :preview-src-list="[image.imageUrl]" preview-teleported />
              <div class="truncate mt-1">{{ image.label || image.controlType }}</div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <template #footer>
      <div class="flex justify-between">
        <el-button type="primary" plain :loading="loading" @click="generate">重新生成方案</el-button>
        <div class="flex gap-2">
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" plain :disabled="!plan" :loading="applying" @click="apply">应用到分镜</el-button>
          <el-button type="success" :disabled="!plan" :loading="applying" @click="applyAndGenerate">应用并生成首帧</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { shotApi } from '@/api/drama'
import type { ShotAiVisualPlan } from '@/types/drama'

const emit = defineEmits<{
  (e: 'success', shotId: number): void
  (e: 'generate', shotId: number): void
}>()
const visible = ref(false)
const loading = ref(false)
const applying = ref(false)
const shotId = ref<number>()
const instruction = ref('')
const plan = ref<ShotAiVisualPlan | null>(null)

async function generate() {
  if (!shotId.value) return
  loading.value = true
  try {
    plan.value = await shotApi.generateAiVisualPlan(shotId.value, { instruction: instruction.value || undefined }) || null
  } catch (e: any) {
    ElMessage.error(e.message || 'AI 视觉方案生成失败')
  } finally {
    loading.value = false
  }
}

async function open(id: number) {
  shotId.value = id
  plan.value = null
  instruction.value = ''
  visible.value = true
  await generate()
}

async function apply() {
  await savePlan(false)
}

async function applyAndGenerate() {
  await savePlan(true)
}

async function savePlan(openGenerator: boolean) {
  if (!shotId.value || !plan.value) return
  applying.value = true
  try {
    await shotApi.applyAiVisualPlan(shotId.value, plan.value)
    ElMessage.success('AI 视觉方案已应用到分镜')
    visible.value = false
    if (openGenerator) {
      emit('generate', shotId.value)
    } else {
      emit('success', shotId.value)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '应用 AI 视觉方案失败')
  } finally {
    applying.value = false
  }
}

function copy(text?: string) {
  if (!text) return ElMessage.warning('内容为空')
  navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制'))
}

defineExpose({ open })
</script>
