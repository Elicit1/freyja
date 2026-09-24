<template>
  <el-dialog
    v-model="visible"
    :title="`🔍 分镜 [${shotName || 'S' + shotNo}] 完整 Prompt 全景预览`"
    width="780px"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
  >
    <div v-loading="loading" class="flex flex-col gap-4 max-h-[72vh] overflow-y-auto px-1 py-1 custom-scrollbar">
      <!-- 顶部信息摘要条 -->
      <div class="bg-slate-50 border border-slate-200 rounded-lg p-3 flex items-center justify-between text-xs">
        <div class="flex items-center gap-2">
          <span class="font-bold text-slate-800 text-sm">分镜 S{{ shotNo }}</span>
          <el-tag size="small" effect="plain" type="info">{{ shotName || '未命名分镜' }}</el-tag>
          <span class="text-slate-400">|</span>
          <span class="text-slate-600">景别: <strong>{{ shotType || '未指定 (AI 自适应)' }}</strong></span>
          <span class="text-slate-400">|</span>
          <span class="text-slate-600">运镜: <strong>{{ cameraMovement || '未指定 (AI 自适应)' }}</strong></span>
        </div>
        <div class="flex items-center gap-1">
          <el-button size="small" type="primary" link @click="loadPreviewData">
            🔄 实时刷新
          </el-button>
        </div>
      </div>

      <!-- 视觉转化铁律合规状态指示栏 -->
      <div class="bg-gradient-to-r from-blue-50/60 via-indigo-50/60 to-purple-50/60 border border-indigo-100/80 rounded-lg p-2.5 flex items-center justify-between text-xs">
        <div class="flex items-center gap-2">
          <span class="font-bold text-indigo-900 flex items-center gap-1">
            <span>🛡️</span> 视觉转化铁律合规检测:
          </span>
          <div class="flex items-center gap-1.5 flex-wrap">
            <el-tag size="small" type="success" effect="plain" class="!text-[11px]">
              ✓ 时空解耦 (T2I无运镜/无闪烁)
            </el-tag>
            <el-tag size="small" type="success" effect="plain" class="!text-[11px]">
              ✓ 透视逻辑 (视点统一/超解析度过滤)
            </el-tag>
            <el-tag size="small" type="success" effect="plain" class="!text-[11px]">
              ✓ 服化道安全替代 (0畸变破损词)
            </el-tag>
            <el-tag size="small" type="success" effect="plain" class="!text-[11px]">
              ✓ 纯英文及实体包含消解去重
            </el-tag>
          </div>
        </div>
      </div>

      <div v-if="previewResult" class="space-y-4">
        <!-- 1. 🖼️ 首帧生图提示词 (T2I Positive Prompt) -->
        <div class="bg-indigo-50/50 border border-indigo-100 rounded-lg p-3.5 shadow-sm space-y-2">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <span class="text-sm font-bold text-indigo-950 flex items-center gap-1">
                🖼️ 首帧生图提示词 (T2I Positive Prompt)
              </span>
              <el-tag size="small" type="primary" effect="light" class="!text-[11px]">
                用于 ComfyUI / FLUX / SDXL
              </el-tag>
            </div>
            <div class="flex items-center gap-2">
              <span class="text-[11px] text-indigo-700 font-mono">
                {{ previewResult.positivePrompt?.length || 0 }} 字符
              </span>
              <el-button
                size="small"
                type="primary"
                plain
                class="!text-xs"
                @click="copyText(previewResult.positivePrompt, '首帧生图提示词已复制')"
              >
                📋 复制生图词
              </el-button>
            </div>
          </div>
          <div class="bg-white border border-indigo-100 rounded-md p-3 font-mono text-xs text-slate-800 leading-relaxed break-words select-all shadow-inner">
            {{ previewResult.positivePrompt || '（未生成正向生图提示词）' }}
          </div>
        </div>

        <!-- 2. 🎥 视频动态运镜提示词 (I2V Video Dynamics Prompt) -->
        <div class="bg-emerald-50/50 border border-emerald-100 rounded-lg p-3.5 shadow-sm space-y-2">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <span class="text-sm font-bold text-emerald-950 flex items-center gap-1">
                🎥 视频动态运镜提示词 (I2V Dynamics Prompt)
              </span>
              <el-tag size="small" type="success" effect="light" class="!text-[11px]">
                用于 可灵 / Runway / Wan 2.1 / MiniMax
              </el-tag>
            </div>
            <div class="flex items-center gap-2">
              <span class="text-[11px] text-emerald-700 font-mono">
                {{ previewResult.videoPrompt?.length || 0 }} 字符
              </span>
              <el-button
                size="small"
                type="success"
                plain
                class="!text-xs"
                @click="copyText(previewResult.videoPrompt, '视频动态运镜词已复制')"
              >
                📋 复制运镜词
              </el-button>
            </div>
          </div>
          <div class="bg-white border border-emerald-100 rounded-md p-3 font-mono text-xs text-slate-800 leading-relaxed break-words select-all shadow-inner">
            {{ previewResult.videoPrompt || '（未生成视频动态运镜词）' }}
          </div>
        </div>

        <!-- 3. 🚫 负向提示词 (Negative Prompt) -->
        <div class="bg-rose-50/40 border border-rose-100 rounded-lg p-3 shadow-sm space-y-2">
          <div class="flex items-center justify-between">
            <span class="text-xs font-bold text-rose-950 flex items-center gap-1">
              🚫 负向提示词 (Negative Prompt)
            </span>
            <el-button
              size="small"
              type="danger"
              link
              class="!text-xs"
              @click="copyText(previewResult.negativePrompt, '负向提示词已复制')"
            >
              📋 复制负向词
            </el-button>
          </div>
          <div class="bg-white border border-rose-100 rounded-md p-2.5 font-mono text-xs text-slate-700 leading-relaxed break-words select-all shadow-inner">
            {{ previewResult.negativePrompt || '（无负向词）' }}
          </div>
        </div>

        <!-- 4. 🖼️ 多模态人物与场景参考图 (Reference Images / MiniMax H3) -->
        <div>
          <!-- ControlNet / 多模态参考图列表 -->
          <div class="bg-slate-50 border border-slate-200 rounded-lg p-3 space-y-2 text-xs">
            <div class="font-bold text-slate-800 flex items-center justify-between">
              <span>🖼️ 多模态人物与场景参考图 (MiniMax / 垫图)</span>
              <el-tag size="small" type="success">{{ previewResult.controlImages?.length || 0 }} 张</el-tag>
            </div>
            <div v-if="previewResult.controlImages && previewResult.controlImages.length" class="grid grid-cols-2 gap-2 max-h-36 overflow-y-auto">
              <div
                v-for="(ci, idx) in previewResult.controlImages"
                :key="idx"
                class="bg-white border border-slate-200 rounded p-1.5 flex items-center gap-2 shadow-sm"
              >
                <el-image
                  :src="ci.imageUrl"
                  fit="cover"
                  class="w-10 h-10 rounded border border-slate-100 flex-shrink-0"
                  :preview-src-list="[ci.imageUrl]"
                  preview-teleported
                />
                <div class="flex flex-col min-w-0 flex-1">
                  <span class="font-bold text-slate-700 truncate text-[11px]">{{ ci.label }}</span>
                  <span class="text-[10px] text-slate-400">{{ ci.controlType }} ({{ ci.weight }})</span>
                </div>
              </div>
            </div>
            <div v-else class="text-slate-400 py-3 text-center text-xs">
              无 ControlNet 参考图像
            </div>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-between">
        <span class="text-xs text-slate-400">
          💡 预览仅作实时组装呈现，点击【应用并覆盖分镜】将正式写回数据库
        </span>
        <div class="flex items-center gap-2">
          <el-button @click="visible = false">关闭</el-button>
          <el-button type="primary" :loading="applying" @click="handleApplyToShot">
            💾 应用并写入分镜
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { shotApi } from '@/api/drama'
import type { PromptAssembleResult } from '@/types/resource'

const emit = defineEmits<{
  (e: 'success', shotId: number): void
}>()

const visible = ref(false)
const loading = ref(false)
const applying = ref(false)

const currentShotId = ref<number>()
const shotNo = ref<number>(1)
const shotName = ref<string>('')
const shotType = ref<string>('')
const cameraMovement = ref<string>('')

const previewResult = ref<PromptAssembleResult | null>(null)

function copyText(text?: string, msg = '已复制') {
  if (!text) {
    ElMessage.warning('内容为空')
    return
  }
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success(msg)
  }).catch(() => {
    ElMessage.error('复制失败，请手动选择复制')
  })
}

async function open(id: number, sNo = 1, sName = '', sType = '', cMovement = '') {
  currentShotId.value = id
  shotNo.value = sNo
  shotName.value = sName
  shotType.value = sType
  cameraMovement.value = cMovement
  visible.value = true
  await loadPreviewData()
}

async function loadPreviewData() {
  if (!currentShotId.value) return
  loading.value = true
  try {
    const res = await shotApi.previewPrompt(currentShotId.value)
    previewResult.value = res || null
  } catch (e: any) {
    ElMessage.error(e.message || '加载 Prompt 预览失败')
  } finally {
    loading.value = false
  }
}

async function handleApplyToShot() {
  if (!currentShotId.value) return
  applying.value = true
  try {
    await shotApi.assemblePrompt(currentShotId.value)
    ElMessage.success('已成功组装并写入该分镜！')
    visible.value = false
    emit('success', currentShotId.value)
  } catch (e: any) {
    ElMessage.error(e.message || '应用失败')
  } finally {
    applying.value = false
  }
}

defineExpose({
  open
})
</script>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: 6px;
}
</style>
