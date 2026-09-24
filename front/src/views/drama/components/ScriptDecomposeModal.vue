<template>
  <el-dialog
    v-model="visible"
    title="⚡ AI 剧本智能拆解与角色提取工作台"
    width="92%"
    top="3vh"
    destroy-on-close
    :close-on-click-modal="false"
    class="custom-decompose-dialog !rounded-2xl"
    @close="handleDialogClose"
  >
    <div class="h-[78vh] flex flex-col lg:flex-row gap-4 overflow-hidden">
      <!-- ======================================================== -->
      <!-- 1. 左侧：模型配置与剧本文本输入区 (380px ~ 420px) -->
      <!-- ======================================================== -->
      <div class="w-full lg:w-[420px] flex-shrink-0 flex flex-col bg-slate-50 rounded-xl p-4 border border-gray-200 overflow-y-auto custom-scrollbar">
        <div class="flex items-center justify-between mb-3">
          <div class="flex items-center gap-2 font-bold text-gray-800 text-sm">
            <span class="text-lg">⚙️</span> 模型与参数配置
          </div>
          <el-button
            type="primary"
            link
            size="small"
            class="!text-xs flex items-center gap-1 font-semibold text-blue-600 hover:text-blue-700"
            @click="openHistoryDrawer"
          >
            📜 拆解历史
          </el-button>
        </div>

        <!-- 已关联短剧基础信息横幅 (从短剧项目进入时自动带出) -->
        <div v-if="boundDrama" class="mb-3 bg-gradient-to-r from-blue-50 to-indigo-50 border border-blue-200 rounded-xl p-2.5 flex items-center justify-between text-xs shadow-sm">
          <div class="flex items-center gap-2 truncate flex-1 mr-2">
            <span class="text-base flex-shrink-0">🎬</span>
            <div class="truncate">
              <div class="font-bold text-blue-950 truncate max-w-[210px]" :title="boundDrama.title">
                《{{ boundDrama.title }}》
              </div>
              <div class="text-[11px] text-blue-600 truncate mt-0.5">
                已同步短剧画幅与风格设定
              </div>
            </div>
          </div>
          <div class="flex flex-col items-end gap-1 flex-shrink-0">
            <el-tag size="small" type="primary" effect="light" class="font-mono text-[10px]">
              {{ boundDrama.aspectRatio || '9:16' }}
            </el-tag>
            <span class="text-[10px] text-gray-500 truncate max-w-[90px] font-mono">
              {{ boundDrama.stylePreset }}
            </span>
          </div>
        </div>

        <el-form label-position="top" size="small" class="space-y-2">
          <!-- AI 提供商与模型选择 -->
          <div class="grid grid-cols-2 gap-2">
            <el-form-item label="AI 提供商" required class="!mb-2">
              <el-select
                v-model="form.providerId"
                placeholder="选择提供商"
                class="w-full"
                @change="handleProviderChange"
                :loading="providersLoading"
                :disabled="isStreaming"
              >
                <el-option
                  v-for="p in providerList"
                  :key="p.id"
                  :label="p.providerName"
                  :value="p.id"
                >
                  <div class="flex items-center justify-between">
                    <span>{{ p.providerName }}</span>
                    <el-tag size="small" type="info" class="font-mono text-[10px]">{{ p.providerType }}</el-tag>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>

            <el-form-item label="AI 模型" required class="!mb-2">
              <el-select
                v-model="form.modelCode"
                placeholder="选择模型"
                class="w-full"
                :loading="modelsLoading"
                :disabled="isStreaming || !form.providerId"
              >
                <el-option
                  v-for="m in modelList"
                  :key="m.modelCode"
                  :label="m.modelName || m.modelCode"
                  :value="m.modelCode"
                >
                  <div class="flex items-center justify-between">
                    <span>{{ m.modelName || m.modelCode }}</span>
                    <span class="text-[11px] text-gray-400 font-mono">{{ m.modelCode }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>
          </div>

          <!-- 画面画幅与风格偏好 -->
          <div class="grid grid-cols-2 gap-2">
            <el-form-item label="画幅比例" class="!mb-2">
              <DictSelect
                v-model="form.aspectRatio"
                dict-type="drama_aspect_ratio"
                placeholder="画幅"
                class="w-full"
                :disabled="isStreaming"
              />
            </el-form-item>

            <el-form-item label="画面风格预设" class="!mb-2">
              <DictSelect
                v-model="form.stylePreset"
                dict-type="drama_style_preset"
                placeholder="风格"
                class="w-full"
                :disabled="isStreaming"
              />
            </el-form-item>
          </div>

          <!-- 目标起始集号与单集时长 -->
          <div class="grid grid-cols-2 gap-2">
            <el-form-item label="目标起始集号" class="!mb-2">
              <el-input-number
                v-model="form.startEpisodeNo"
                :min="1"
                :max="999"
                placeholder="如 1 或 2"
                class="!w-full"
                controls-position="right"
                :disabled="isStreaming"
              />
            </el-form-item>

            <el-form-item label="单集目标时长 (秒)" class="!mb-2">
              <el-input-number
                v-model="form.targetDurationPerEpisode"
                :min="30"
                :max="1800"
                :step="30"
                class="!w-full"
                controls-position="right"
                :disabled="isStreaming"
              />
            </el-form-item>
          </div>

          <!-- 剪辑节奏与镜头粒度偏好 (防碎镜头) -->
          <el-form-item label="剪辑节奏与镜头粒度" class="!mb-2">
            <el-select v-model="form.pacingPreset" placeholder="选择镜头节奏" class="w-full" :disabled="isStreaming">
              <el-option value="STANDARD" label="⚡ 标准工业短剧 (推荐 · 5~8s/镜，动作单元聚合)">
                <div class="flex items-center justify-between">
                  <span class="font-medium text-slate-800">⚡ 标准工业短剧 (推荐)</span>
                  <span class="text-[11px] text-emerald-600 font-mono">5~8s/镜 · 动作聚合防碎</span>
                </div>
              </el-option>
              <el-option value="CINEMATIC_LONG" label="🎬 电影感长镜头 (7~12s/镜，极少切镜，重运镜空间)">
                <div class="flex items-center justify-between">
                  <span class="font-medium text-slate-800">🎬 电影感长镜头</span>
                  <span class="text-[11px] text-blue-600 font-mono">7~12s/镜 · 极少切镜</span>
                </div>
              </el-option>
              <el-option value="FAST_PACED" label="💥 快节奏紧凑剪辑 (2~4s/镜，紧凑切镜，密集反应)">
                <div class="flex items-center justify-between">
                  <span class="font-medium text-slate-800">💥 快节奏紧凑剪辑</span>
                  <span class="text-[11px] text-amber-600 font-mono">2~4s/镜 · 高频冲突</span>
                </div>
              </el-option>
            </el-select>
          </el-form-item>

          <!-- 视觉风格基调与导演风格指南 (Worker 原生融合) -->
          <el-form-item label="视觉基调指南 (导演风格)" class="!mb-2">
            <div class="w-full space-y-1">
              <el-input
                v-model="form.styleTone"
                type="textarea"
                :rows="2"
                placeholder="为全剧确定统一的视觉影调与摄影风格（大模型源头自然融入，无需程序机械拼接）..."
                :disabled="isStreaming"
              />
              <div class="flex flex-wrap items-center gap-1 text-xs">
                <span class="text-gray-400 text-[10px]">快捷预设:</span>
                <el-tag
                  v-for="tag in stylePresets"
                  :key="tag.label"
                  size="small"
                  class="cursor-pointer hover:opacity-80 transition-all select-none text-[11px]"
                  type="info"
                  @click="!isStreaming && (form.styleTone = tag.prompt)"
                >
                  {{ tag.label }}
                </el-tag>
              </div>
            </div>
          </el-form-item>

          <div class="mb-3 rounded-lg border border-indigo-100 bg-indigo-50/40 p-3 space-y-3">
            <div class="text-xs font-semibold text-slate-700">🧩 拆解阶段 AI Skills</div>
            <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
              <div class="space-y-2">
                <div class="flex items-center justify-between text-xs"><span>Planner · 整章分段</span><el-switch v-model="plannerDynamicSkills" :disabled="isStreaming" size="small" active-text="按需调用" /></div>
                <SkillSelector v-model="plannerRequiredSkills" mode="API" :disabled="isStreaming" :dynamic-enabled="plannerDynamicSkills" />
              </div>
              <div class="space-y-2">
                <div class="flex items-center justify-between text-xs"><span>Worker · 各段分镜</span><el-switch v-model="workerDynamicSkills" :disabled="isStreaming" size="small" active-text="按需调用" /></div>
                <SkillSelector v-model="workerRequiredSkills" mode="API" :disabled="isStreaming" :dynamic-enabled="workerDynamicSkills" />
              </div>
            </div>
            <p class="text-[11px] text-slate-500">必用 Skill 在模型调用前加载；开启按需调用后，AI 可在执行中加载目录里的其他 Skill。</p>
          </div>

          <!-- 文件导入卡片 (支持拖拽 / 点击上传 TXT / MD) -->
          <div class="mb-2">
            <el-upload
              drag
              action=""
              :auto-upload="false"
              :show-file-list="false"
              accept=".txt,.md,.text"
              class="custom-script-uploader w-full"
              :on-change="handleFileChange"
              :disabled="isStreaming"
            >
              <div class="p-2 flex flex-col items-center justify-center text-center">
                <el-icon class="text-2xl text-blue-500 mb-1"><UploadFilled /></el-icon>
                <div class="text-xs text-gray-700 font-bold">
                  点击或拖拽上传 <span class="text-blue-600">.txt / .md</span> 剧本文件
                </div>
                <div class="text-[10px] text-gray-400 mt-0.5">
                  支持 UTF-8 / GBK 编码，单文件限 2MB（约 70 万字）
                </div>
              </div>
            </el-upload>

            <!-- 已上传文件徽章与章节筛选 -->
            <div v-if="uploadedFile" class="mt-2 bg-blue-50/80 border border-blue-200 rounded-lg p-2 flex flex-col gap-1.5 text-xs">
              <div class="flex items-center justify-between">
                <div class="flex items-center gap-1.5 text-blue-900 font-bold truncate">
                  <span>📄</span>
                  <span class="truncate max-w-[200px]" :title="uploadedFile.name">{{ uploadedFile.name }}</span>
                  <span class="text-[11px] text-blue-600 font-mono">({{ uploadedFile.size }})</span>
                </div>
                <el-button link type="danger" size="small" :disabled="isStreaming" @click="handleRemoveFile">
                  <el-icon><Close /></el-icon> 清除
                </el-button>
              </div>

              <!-- 章节切片检测与筛选 -->
              <div v-if="detectedChapters.length > 0" class="flex items-center gap-2 pt-1 border-t border-blue-200/60">
                <span class="text-[11px] text-blue-800 flex-shrink-0 font-medium">
                  🔍 自动识别到 {{ detectedChapters.length }} 个章节:
                </span>
                <el-select
                  v-model="selectedChapterIndex"
                  size="small"
                  class="flex-1"
                  :disabled="isStreaming"
                  @change="handleChapterSelect"
                >
                  <el-option :value="-1" label="📖 全书/全文内容" />
                  <el-option
                    v-for="(ch, cIdx) in detectedChapters"
                    :key="cIdx"
                    :value="cIdx"
                    :label="`${ch.title} (${ch.content.length}字)`"
                  />
                </el-select>
              </div>
            </div>
          </div>

          <!-- 原始故事文本输入区 -->
          <el-form-item class="!mb-3">
            <template #label>
              <div class="flex items-center justify-between w-full">
                <span class="font-bold text-gray-800">原始剧本 / 小说故事文本</span>
                <span
                  v-if="textLengthStatus.type !== 'empty'"
                  :class="['text-[11px] px-2 py-0.5 rounded-full border font-mono transition-all', textLengthStatus.badgeClass]"
                >
                  {{ rawTextLength.toLocaleString() }} 字 · {{ textLengthStatus.label }}
                </span>
                <span v-else class="text-xs text-gray-400 font-mono">0 字</span>
              </div>
            </template>

            <!-- 快捷填充示例 -->
            <div class="flex items-center gap-1.5 mb-1.5 flex-wrap">
              <span class="text-[11px] text-gray-500">快捷填充:</span>
              <el-button link type="primary" size="small" :disabled="isStreaming" @click="fillSample('wargod')">
                战神赘婿
              </el-button>
              <el-button link type="primary" size="small" :disabled="isStreaming" @click="fillSample('ceo')">
                霸总甜宠
              </el-button>
              <el-button link type="primary" size="small" :disabled="isStreaming" @click="fillSample('suspense')">
                都市悬疑
              </el-button>
              <el-button link type="info" size="small" :disabled="isStreaming" @click="handleClearText">
                清空
              </el-button>
            </div>

            <el-input
              v-model="form.rawText"
              type="textarea"
              :rows="11"
              placeholder="在此粘贴小说章节、故事大纲或自然语言剧情描述。AI 将自动分集、提取出场人物外观/服饰提示词、环境场景并生成带角色对白的逐镜分镜卡片流..."
              class="font-sans text-xs"
              :disabled="isStreaming"
            />

            <!-- 篇幅提示或超限警告 -->
            <div
              v-if="textLengthStatus.type === 'warning'"
              class="mt-1.5 text-[11px] text-amber-700 bg-amber-50 border border-amber-200 rounded p-1.5 flex items-start gap-1 w-full leading-relaxed"
            >
              <span>💡</span>
              <span>{{ textLengthStatus.tip }}</span>
            </div>
            <div
              v-else-if="textLengthStatus.type === 'exceeded'"
              class="mt-1.5 text-[11px] text-rose-700 bg-rose-50 border border-rose-200 rounded p-1.5 flex items-start gap-1 w-full leading-relaxed font-medium"
            >
              <span>⚠️</span>
              <span>{{ textLengthStatus.tip }}</span>
            </div>
          </el-form-item>
        </el-form>

        <!-- 底部拆解按钮 -->
        <div class="mt-auto pt-3 border-t border-gray-200">
          <el-button
            v-if="!isStreaming"
            type="primary"
            size="large"
            class="w-full !font-bold shadow-md shadow-blue-500/20"
            :disabled="!canDecompose"
            @click="handleStartDecompose()"
          >
            <span v-if="textLengthStatus.type === 'exceeded'">
              ⚠️ 文本超出 3.5 万字上限（请在上方选择单章拆解）
            </span>
            <span v-else class="flex items-center gap-1.5">
              <span>🚀</span> 开始拆解并查看实时日志
            </span>
          </el-button>
          <el-button
            v-if="!isStreaming"
            type="success"
            size="large"
            class="w-full !font-bold !ml-0 !mt-2"
            :disabled="!canDecompose"
            :loading="startingInBackground"
            @click="handleStartInBackground"
          >
            后台运行，稍后从任务中心打开
          </el-button>
          <el-button
            v-else
            type="primary"
            plain
            size="large"
            class="w-full !font-bold !ml-0"
            @click="handleMoveToBackground"
          >
            后台继续并关闭窗口 (已耗时 {{ elapsedSeconds }}s)
          </el-button>
          <p class="mt-2 text-[11px] text-slate-500">关闭窗口后任务继续运行，可在任务中心重新打开并查看日志、确认入库。</p>
        </div>
      </div>

      <!-- ======================================================== -->
      <!-- 2. 右侧：多维结构化审查看板 (Tabs) / 实时流式控制台 -->
      <!-- ======================================================== -->
      <div class="flex-1 flex flex-col bg-white rounded-xl border border-gray-200 overflow-hidden relative shadow-sm">
        <!-- 未拆解空状态 -->
        <div
          v-if="!result && !isStreaming"
          class="h-full flex flex-col items-center justify-center p-8 text-center text-gray-400 bg-slate-50/40"
        >
          <div class="w-20 h-20 rounded-full bg-blue-50 flex items-center justify-center text-4xl mb-4 text-blue-500">
            🎬
          </div>
          <h3 class="text-base font-bold text-gray-700 mb-1.5">等待 AI 拆解输入</h3>
          <p class="text-xs text-gray-400 max-w-md leading-relaxed mb-4">
            在左侧选择 AI 模型并填入剧本故事文本，点击「开始 AI 智能拆解」。系统将通过 SSE 流式驱动大模型实时输出角色资产库、场景资产库以及分集分镜对白流。
          </p>
          <div class="flex items-center gap-3 text-xs text-gray-500 bg-white px-4 py-2 rounded-lg border border-gray-200">
            <span>✨ 实时打字机流式呈现</span>
            <span class="text-gray-300">|</span>
            <span>🎭 场景空间与时段识别</span>
            <span class="text-gray-300">|</span>
            <span>💬 角色台词对白气泡</span>
          </div>
        </div>

        <!-- 实时流式生成思考中终端 (纯白现代画框风格) -->
        <div
          v-else-if="isStreaming"
          class="h-full flex flex-col bg-white text-slate-800 overflow-hidden font-sans border border-slate-200 shadow-sm"
        >
          <!-- 思考中 顶部控制栏 (纯白画框风格) -->
          <div class="px-5 py-3 bg-white border-b border-slate-200 flex items-center justify-between shadow-xs">
            <div class="flex items-center gap-3">
              <!-- AI 分析动态微标 -->
              <div class="relative flex items-center justify-center w-8 h-8 rounded-lg bg-blue-50 border border-blue-200 text-blue-600 flex-shrink-0 shadow-xs">
                <el-icon class="text-base is-loading text-blue-600"><Loading /></el-icon>
              </div>
              <div class="flex flex-col">
                <div class="flex items-center gap-2">
                  <span class="text-sm font-bold text-slate-900 tracking-wide flex items-center gap-1.5">
                    AI 正在深度思考与分镜规划
                    <span class="inline-flex items-center gap-0.5 text-blue-500 text-xs font-mono animate-pulse">
                      <span>●</span><span>●</span><span>●</span>
                    </span>
                  </span>
                </div>
                <div class="text-[11px] text-slate-500 flex items-center gap-2 mt-0.5 font-mono">
                  <span>⏱️ 已思考 {{ elapsedSeconds }}s</span>
                  <span>·</span>
                  <span>📝 {{ streamOutputText.length.toLocaleString() }} 字符</span>
                </div>
              </div>
            </div>

          </div>

          <!-- 阶段流水线状态胶囊条 -->
          <div class="px-5 py-2.5 bg-slate-50/80 border-b border-slate-200 flex flex-col gap-2">
            <div class="flex items-center justify-between text-xs overflow-x-auto gap-2">
              <div class="flex items-center gap-2.5 flex-wrap">
                <!-- 步骤 1: 剧情分段 -->
                <div
                  class="flex items-center gap-1.5 font-medium transition-all"
                  :class="currentThinkingPhase === 1 ? 'text-blue-600 font-bold bg-blue-50 px-2 py-0.5 rounded border border-blue-200' : currentThinkingPhase > 1 ? 'text-emerald-600 font-semibold' : 'text-slate-400'"
                >
                  <span v-if="currentThinkingPhase === 1" class="animate-spin text-xs">⏳</span>
                  <span v-else-if="currentThinkingPhase > 1" class="text-xs font-bold text-emerald-600">✓</span>
                  <span v-else class="text-xs text-slate-400">1.</span>
                  <span>🎯 剧情分段</span>
                </div>
                <span class="text-slate-300">→</span>

                <!-- 步骤 2: 场景·角色·道具资产深度提炼 -->
                <div
                  class="flex items-center gap-1.5 font-medium transition-all"
                  :class="currentThinkingPhase === 2 ? 'text-purple-600 font-bold bg-purple-50 px-2 py-0.5 rounded border border-purple-200 shadow-xs animate-pulse' : currentThinkingPhase > 2 ? 'text-emerald-600 font-semibold' : 'text-slate-400'"
                >
                  <span v-if="currentThinkingPhase === 2" class="animate-spin text-xs">⏳</span>
                  <span v-else-if="currentThinkingPhase > 2" class="text-xs font-bold text-emerald-600">✓</span>
                  <span v-else class="text-xs text-slate-400">2.</span>
                  <span>🏛️ 场景·角色·道具提炼</span>
                </div>
                <span class="text-slate-300">→</span>

                <!-- 步骤 3: 分镜并行生成 -->
                <div
                  class="flex items-center gap-1.5 font-medium transition-all"
                  :class="currentThinkingPhase === 3 ? 'text-blue-600 font-bold bg-blue-50 px-2 py-0.5 rounded border border-blue-200' : currentThinkingPhase > 3 ? 'text-emerald-600 font-semibold' : 'text-slate-400'"
                >
                  <span v-if="currentThinkingPhase === 3" class="animate-spin text-xs">⏳</span>
                  <span v-else-if="currentThinkingPhase > 3" class="text-xs font-bold text-emerald-600">✓</span>
                  <span v-else class="text-xs text-slate-400">3.</span>
                  <span>⚡ 分镜并行生成</span>
                </div>
                <span class="text-slate-300">→</span>

                <!-- 步骤 4: 确定性合并与体检 -->
                <div
                  class="flex items-center gap-1.5 font-medium transition-all"
                  :class="currentThinkingPhase === 4 ? 'text-blue-600 font-bold bg-blue-50 px-2 py-0.5 rounded border border-blue-200' : currentThinkingPhase > 4 ? 'text-emerald-600 font-semibold' : 'text-slate-400'"
                >
                  <span v-if="currentThinkingPhase === 4" class="animate-spin text-xs">⏳</span>
                  <span v-else-if="currentThinkingPhase > 4" class="text-xs font-bold text-emerald-600">✓</span>
                  <span v-else class="text-xs text-slate-400">4.</span>
                  <span>🛡️ 确定性合并与体检</span>
                </div>
                <span class="text-slate-300">→</span>

                <!-- 步骤 5: 视听 Prompt 装配 -->
                <div
                  class="flex items-center gap-1.5 font-medium transition-all"
                  :class="currentThinkingPhase === 5 ? 'text-blue-600 font-bold bg-blue-50 px-2 py-0.5 rounded border border-blue-200' : currentThinkingPhase > 5 ? 'text-emerald-600 font-semibold' : 'text-slate-400'"
                >
                  <span v-if="currentThinkingPhase === 5" class="animate-spin text-xs">⏳</span>
                  <span v-else-if="currentThinkingPhase > 5" class="text-xs font-bold text-emerald-600">✓</span>
                  <span v-else class="text-xs text-slate-400">5.</span>
                  <span>🎨 视听 Prompt 装配</span>
                </div>
              </div>
            </div>

            <!-- 前置资产提炼动态就绪看板 (当已提取到角色/场景/道具时即时浮现) -->
            <div
              v-if="effectiveDiscoveredAssets || currentThinkingPhase >= 2"
              class="flex flex-col gap-1.5 pt-2 border-t border-slate-200/70 transition-all bg-white/70 px-3 py-2 rounded-lg border border-purple-100 shadow-xs"
            >
              <div class="flex items-center justify-between text-[11px]">
                <div class="flex items-center gap-1.5 font-bold text-purple-800">
                  <span class="text-xs">🏛️</span>
                  <span>前置资产提炼与强 ID 建档看板:</span>
                  <span
                    v-if="currentThinkingPhase === 2"
                    class="text-[10px] px-1.5 py-0.2 rounded bg-purple-100 text-purple-700 font-mono font-normal animate-pulse"
                  >
                    正在深度分析并对齐资产库...
                  </span>
                  <span
                    v-else
                    class="text-[10px] px-1.5 py-0.2 rounded bg-emerald-100 text-emerald-700 font-mono font-normal"
                  >
                    ✓ 资产库已锁定，已注入 Worker 并行执行
                  </span>
                </div>
              </div>
              <div class="flex items-center gap-2 flex-wrap text-xs pt-0.5">
                <!-- 角色徽章 -->
                <div class="flex items-center gap-1 px-2 py-1 rounded bg-amber-50/90 text-amber-900 border border-amber-200/80 text-[11px]">
                  <span class="font-bold text-amber-800">👤 核心角色 ({{ effectiveDiscoveredAssets?.characters?.length || 0 }}):</span>
                  <span class="font-mono text-slate-700 max-w-[200px] truncate">
                    {{ (effectiveDiscoveredAssets?.characters || []).map((c: any) => c.name || c.canonicalName).join('、') || '正在解析...' }}
                  </span>
                </div>
                <!-- 场景徽章 -->
                <div class="flex items-center gap-1 px-2 py-1 rounded bg-cyan-50/90 text-cyan-900 border border-cyan-200/80 text-[11px]">
                  <span class="font-bold text-cyan-800">🏞️ 空间场景 ({{ effectiveDiscoveredAssets?.scenes?.length || 0 }}):</span>
                  <span class="font-mono text-slate-700 max-w-[220px] truncate">
                    {{ (effectiveDiscoveredAssets?.scenes || []).map((s: any) => s.sceneName).join('、') || '正在解析...' }}
                  </span>
                </div>
                <!-- 道具徽章 -->
                <div class="flex items-center gap-1 px-2 py-1 rounded bg-purple-50/90 text-purple-900 border border-purple-200/80 text-[11px]">
                  <span class="font-bold text-purple-800">📦 关键道具 ({{ effectiveDiscoveredAssets?.props?.length || 0 }}):</span>
                  <span class="font-mono text-slate-700 max-w-[200px] truncate">
                    {{ (effectiveDiscoveredAssets?.props || []).map((p: any) => p.name).join('、') || '正在解析...' }}
                  </span>
                </div>
              </div>
            </div>

            <!-- Worker 分段并行状态矩阵 -->
            <div v-if="liveSegments.length > 0" class="flex items-center gap-2 flex-wrap pt-1.5 border-t border-slate-200/60">
              <span class="text-[11px] font-semibold text-slate-500 flex-shrink-0">分段执行状态:</span>
              <div
                v-for="seg in liveSegments"
                :key="seg.id"
                class="flex items-center gap-1 px-2 py-0.5 rounded text-[11px] border font-mono transition-all cursor-pointer select-none"
                :class="seg.status === 'SUCCESS' ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : seg.status === 'RUNNING' ? 'bg-blue-50 text-blue-700 border-blue-200 animate-pulse' : seg.status === 'FAILED' ? 'bg-rose-50 text-rose-700 border-rose-300 font-bold' : 'bg-slate-100 text-slate-500 border-slate-200'"
                @click="activeChannelTab = seg.id; showThinkingDetails = true"
                :title="'点击直达 ' + seg.id + ' 独立视口'"
              >
                <span v-if="seg.status === 'RUNNING'" class="animate-spin text-[10px]">⏳</span>
                <span v-else-if="seg.status === 'SUCCESS'" class="text-emerald-600 font-bold">✓</span>
                <span v-else-if="seg.status === 'FAILED'" class="text-rose-600 font-bold">❌</span>
                <span v-else class="text-slate-400">•</span>
                <span class="font-bold">{{ seg.id }}</span>
                <span v-if="seg.title" class="text-slate-600 max-w-[80px] truncate">({{ seg.title }})</span>
                <span v-if="seg.shotsCount" class="text-pink-600 font-semibold">{{ seg.shotsCount }}镜</span>
                <el-button
                  v-if="seg.status === 'FAILED' && result?.taskId"
                  size="small"
                  type="danger"
                  link
                  class="!text-[10px] !p-0 ml-1 underline"
                  @click.stop="openWorkerRetryDialog(seg)"
                >
                  重试
                </el-button>
              </div>
            </div>
          </div>

          <div class="px-5 py-2 border-b border-indigo-100 bg-indigo-50/60 text-[11px] text-indigo-900 flex items-center gap-2 flex-wrap">
            <span class="font-bold">Skill 调用记录 {{ skillEvents.length }} 条</span>
            <span v-if="skillEvents.length">最近：{{ skillEventLabel(skillEvents[skillEvents.length - 1]!) }}</span>
            <span v-else>暂无加载事件；调用后会显示在此处及下方 Planner / Worker 日志中</span>
          </div>

          <!-- 折叠/展开的实时 Token 流式终端 (支持多通道 Tab 分流 + 工业等宽优雅排版) -->
          <div
            v-show="showThinkingDetails"
            class="flex-1 flex flex-col bg-slate-50/70 p-4 overflow-hidden relative font-sans text-xs"
          >
            <!-- 终端顶部控制栏：多通道分流 Tab 按钮组与操作功能 -->
            <div class="flex items-center justify-between pb-2.5 mb-2 border-b border-slate-200 gap-2 overflow-x-auto flex-nowrap flex-shrink-0">
              <!-- 左侧多通道 Tab 按钮组 -->
              <div class="flex items-center gap-1.5 overflow-x-auto custom-scrollbar py-0.5">
                <button
                  v-for="ch in channelList"
                  :key="ch.id"
                  type="button"
                  class="px-2.5 py-1 rounded-lg text-xs font-medium transition-all flex items-center gap-1.5 cursor-pointer flex-shrink-0 border shadow-2xs select-none"
                  :class="activeChannelTab === ch.id
                    ? 'bg-blue-600 text-white border-blue-600 font-bold shadow-xs'
                    : 'bg-white hover:bg-slate-100 text-slate-700 border-slate-200 hover:border-slate-300'"
                  @click="activeChannelTab = ch.id"
                >
                  <span v-if="ch.status === 'RUNNING'" class="animate-spin text-[10px]">⏳</span>
                  <span v-else-if="ch.status === 'SUCCESS'" class="text-[10px] font-bold" :class="activeChannelTab === ch.id ? 'text-white' : 'text-emerald-600'">✓</span>
                  <span>{{ ch.name }}</span>
                  <span v-if="ch.shotsCount" class="px-1 py-0.2 rounded text-[10px] font-mono" :class="activeChannelTab === ch.id ? 'bg-blue-700 text-blue-100' : 'bg-pink-50 text-pink-600 border border-pink-100'">{{ ch.shotsCount }}镜</span>
                </button>
              </div>

              <!-- 右侧快捷功能：收起 -->
              <div class="flex items-center gap-2 flex-shrink-0">
                <button
                  type="button"
                  class="px-2.5 py-1 rounded-md border border-slate-200 bg-white hover:bg-slate-100 text-slate-700 text-xs font-medium cursor-pointer transition-all flex items-center gap-1 shadow-xs"
                  @click="showThinkingDetails = false"
                >
                  <span>收起终端</span>
                  <span class="text-[10px] text-slate-500">▲</span>
                </button>
              </div>
            </div>

            <!-- 控制台主体窗口 (高清晰等宽字体，支持独立打字机光标) -->
            <div
              ref="streamConsoleRef"
              class="flex-1 overflow-y-auto custom-scrollbar leading-relaxed whitespace-pre-wrap select-text text-slate-800 pr-3 pb-6 bg-white border border-slate-200 rounded-xl p-4 shadow-xs font-mono text-[12px]"
            >
              {{ currentActiveTabText }}<span class="inline-block w-2 h-4 bg-blue-500 align-middle animate-pulse ml-0.5 shadow-[0_0_8px_rgba(59,130,246,0.6)]"></span>
            </div>

            <!-- 终端底部状态说明与自动滚动开关 -->
            <div class="pt-2.5 border-t border-slate-200 text-[11px] text-slate-500 flex items-center justify-between font-sans">
              <span class="flex items-center gap-1.5">
                <span class="inline-block w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
                <span>💡 提示：点击上方分段 Tab 可自由切换各 Worker 独立视口，查看独立分镜生成打字流</span>
              </span>
              <div class="flex items-center gap-3">
                <label class="flex items-center gap-1 cursor-pointer select-none text-slate-600 hover:text-slate-900 font-medium">
                  <input type="checkbox" v-model="autoScroll" class="rounded border-slate-300 text-blue-600 focus:ring-0 cursor-pointer" />
                  <span>自动滚动</span>
                </label>
                <span class="text-blue-600 font-mono font-bold bg-blue-50 px-2 py-0.5 rounded border border-blue-200">SSE Active</span>
              </div>
            </div>
          </div>

          <!-- 当 showThinkingDetails 为 false 时展示的紧凑极简思考占位条 (整块区域可直接点击展开) -->
          <div
            v-show="!showThinkingDetails"
            class="flex-1 flex flex-col items-center justify-center p-8 text-center bg-slate-50/40 hover:bg-blue-50/20 text-slate-500 border-2 border-dashed border-slate-200 hover:border-blue-300 rounded-xl m-4 cursor-pointer transition-all duration-200 group select-none shadow-xs"
            @click="showThinkingDetails = true"
          >
            <div class="w-14 h-14 rounded-2xl bg-blue-50 border border-blue-100 flex items-center justify-center text-blue-500 mb-3 shadow-xs group-hover:scale-105 transition-transform">
              <el-icon class="text-2xl is-loading"><Loading /></el-icon>
            </div>
            <div class="text-sm font-bold text-slate-800 mb-1 group-hover:text-blue-600 transition-colors">
              AI 正在后台全速思考与分镜规划中...
            </div>
            <div class="text-xs text-slate-500 mb-4 font-mono">
              已生成 {{ streamOutputText.length.toLocaleString() }} 字符 · 已耗时 {{ elapsedSeconds }}s
            </div>
            <div
              class="px-4 py-2 rounded-lg border border-blue-200 bg-white group-hover:bg-blue-50 text-blue-600 text-xs font-semibold cursor-pointer transition-all flex items-center gap-1.5 shadow-xs"
            >
              <span>📜 点击在当前窗口展开实时 AI 流式输出</span>
              <span class="text-[10px]">▼</span>
            </div>
            <div class="text-[11px] text-slate-400 mt-2">
              (点击卡片任意位置均可展开实时打字流，再次点击即可隐藏)
            </div>
          </div>
        </div>

        <!-- 拆解结果审查看板 -->
        <div v-else-if="result" class="h-full flex flex-col overflow-hidden">
          <!-- 顶部思考与规则体检详情折叠条 (纯白画框风格) -->
          <div class="border-b border-indigo-100 bg-gradient-to-r from-blue-50/90 via-indigo-50/70 to-purple-50/70 text-slate-800 transition-all flex-shrink-0">
            <div class="px-5 py-2 flex items-center justify-between">
              <div class="flex items-center gap-2.5 text-xs">
                <span class="w-5 h-5 rounded-full bg-emerald-100 text-emerald-700 border border-emerald-300 flex items-center justify-center text-[10px] font-bold">✓</span>
                <span class="font-bold text-slate-800 flex items-center gap-1.5">
                  ✨ 深度思考与连续性规则体检已就绪
                </span>
                <span class="text-slate-500 font-mono text-[11px] hidden sm:inline">
                  (耗时 {{ elapsedSeconds }}s · 规划 {{ totalShotsCount }} 分镜 · 9 大连续性规则已校验)
                </span>
              </div>
              <div class="flex items-center gap-2">
                <span v-if="result.skillEvents?.length" class="text-[11px] text-indigo-600">Skill 加载 {{ result.skillEvents.filter(e => e.status === 'LOADED' || e.status === 'SUCCESS').length }} 次</span>
                <button
                  type="button"
                  class="text-[11px] px-2.5 py-1 rounded-md bg-blue-50 hover:bg-blue-100 text-blue-700 flex items-center gap-1 cursor-pointer transition-all border border-blue-200 shadow-xs font-medium"
                  @click="showThinkingDetails = !showThinkingDetails"
                >
                  <span>{{ showThinkingDetails ? '收起思考过程' : '查看思考过程' }}</span>
                  <span class="text-[9px]">{{ showThinkingDetails ? '▲' : '▼' }}</span>
                </button>
              </div>
            </div>

            <!-- 展开后的思考过程终端 -->
            <div
              v-show="showThinkingDetails"
              class="px-5 pb-3 pt-1 border-t border-indigo-100/60 bg-white"
            >
              <div class="mb-2 text-[11px] font-bold text-indigo-700">Skill 调用记录（{{ visibleSkillEvents.length }} 条）</div>
              <div class="max-h-32 overflow-y-auto custom-scrollbar bg-indigo-50/50 rounded-lg p-2 font-mono text-[11px] leading-relaxed text-slate-700 border border-indigo-100">
                <div v-for="(event, index) in visibleSkillEvents" :key="index">{{ skillEventLabel(event) }}</div>
                <div v-if="!visibleSkillEvents.length">本次拆解没有 Skill 加载记录。</div>
              </div>
              <div class="max-h-56 overflow-y-auto custom-scrollbar bg-slate-50 rounded-lg p-3 font-mono text-[11px] leading-relaxed text-slate-700 whitespace-pre-wrap select-text border border-slate-200 shadow-inner">
                {{ streamOutputText || '思考记录已归档' }}
              </div>
            </div>
          </div>

          <!-- 部分分段失败/待补救提示横幅 -->
          <div
            v-if="result.status === 'PARTIAL_SUCCESS' || hasFailedSegments"
            class="bg-gradient-to-r from-amber-50 to-orange-50 border-b border-amber-200 px-5 py-2.5 flex items-center justify-between text-xs text-amber-900 flex-shrink-0 shadow-2xs"
          >
            <div class="flex items-center gap-2">
              <span class="text-base">⚠️</span>
              <span class="font-bold">部分分段拆解受阻 (已保留其余全部成功分段)：</span>
              <span class="text-amber-700 hidden md:inline">大模型可能拦截了部分敏感字句或偶发超时。您可在「剧情分段」Tab中微调小说文本或补充指导词后单独重试，系统将自动重新合并并刷新全量分镜！</span>
            </div>
            <el-button
              size="small"
              type="warning"
              plain
              class="!text-xs flex-shrink-0 font-semibold"
              @click="activeTab = 'segments'"
            >
              前往修复失败分段 →
            </el-button>
          </div>

          <!-- 顶部剧本摘要与元数据栏 (浅色画框渐变) -->
          <div class="px-5 py-3 bg-gradient-to-r from-blue-50/90 via-indigo-50/80 to-purple-50/80 border-b border-indigo-100/80 text-slate-800 flex flex-wrap items-center justify-between gap-3 shadow-xs">
            <div class="flex items-center gap-3 flex-1 min-w-[280px]">
              <span class="text-xl">🎬</span>
              <div class="flex-1">
                <div class="flex items-center gap-2">
                  <el-input
                    v-model="result.dramaTitle"
                    size="small"
                    placeholder="短剧名称"
                    class="!w-64 font-bold"
                    :disabled="Boolean(boundDrama)"
                  />
                  <el-tag v-if="boundDrama" size="small" type="primary" effect="plain" class="text-[10px] font-medium">
                    已关联短剧
                  </el-tag>
                  <DictTag :dict-type="'drama_genre'" :value="result.genre" size="small" />
                  <span class="text-xs text-indigo-700 font-mono bg-indigo-50 px-2 py-0.5 rounded border border-indigo-200">
                    {{ form.aspectRatio || '9:16' }}
                  </span>
                </div>
              </div>
            </div>

            <!-- 数据统计标签 (浅色胶囊) -->
            <div class="flex items-center gap-2.5 text-xs text-slate-600 font-mono">
              <span class="bg-white/90 border border-slate-200/80 px-2.5 py-1 rounded-md shadow-xs">
                👤 角色: <strong class="text-amber-600">{{ result.characters?.length || 0 }}</strong> 位
              </span>
              <span class="bg-white/90 border border-slate-200/80 px-2.5 py-1 rounded-md shadow-xs">
                🏞️ 场景: <strong class="text-cyan-600">{{ result.scenes?.length || 0 }}</strong> 处
              </span>
              <span class="bg-white/90 border border-slate-200/80 px-2.5 py-1 rounded-md shadow-xs">
                📦 道具: <strong class="text-purple-600">{{ result.props?.length || 0 }}</strong> 件
              </span>
              <span class="bg-white/90 border border-slate-200/80 px-2.5 py-1 rounded-md shadow-xs">
                📺 集数: <strong class="text-emerald-600">{{ result.episodes?.length || 0 }}</strong> 集
              </span>
              <span class="bg-white/90 border border-slate-200/80 px-2.5 py-1 rounded-md shadow-xs">
                🎞️ 分镜: <strong class="text-pink-600">{{ totalShotsCount }}</strong> 镜
              </span>
            </div>
          </div>

          <!-- 故事大纲条 -->
          <div class="px-5 py-2 bg-slate-50 border-b border-gray-200 flex items-center gap-2 text-xs">
            <span class="font-bold text-gray-600 flex-shrink-0">📖 故事梗概:</span>
            <el-input
              v-model="result.synopsis"
              size="small"
              placeholder="短剧故事大纲..."
              class="flex-1"
            />
          </div>

          <!-- 碎片率与镜头节奏健康指标横幅 (ShotDurationRule 统计) -->
          <div
            v-if="result.fragmentationStats"
            class="px-5 py-2 border-b text-xs flex items-center justify-between transition-colors"
            :class="result.fragmentationStats.isHighFragmentation ? 'bg-amber-50/90 border-amber-200 text-amber-900' : 'bg-emerald-50/70 border-emerald-200 text-emerald-900'"
          >
            <div class="flex items-center gap-2 flex-wrap">
              <span v-if="result.fragmentationStats.isHighFragmentation" class="text-sm">⚠️</span>
              <span v-else class="text-sm">🛡️</span>
              <span class="font-bold">
                {{ result.fragmentationStats.isHighFragmentation ? '镜头碎片率警示' : '影视镜头节奏健康' }}:
              </span>
              <span>
                {{ result.fragmentationStats.warningMessage || `总分镜 ${result.fragmentationStats.totalShots} 镜 | 平均时长 ${result.fragmentationStats.averageDuration}s | 标准镜头 (5~8s) 占比 ${(result.fragmentationStats.totalShots ? result.fragmentationStats.normalShots / result.fragmentationStats.totalShots * 100 : 0).toFixed(0)}%` }}
              </span>
            </div>
            <div class="flex items-center gap-2.5 font-mono text-[11px] flex-shrink-0">
              <span class="bg-white/90 px-2 py-0.5 rounded border border-slate-200/80 shadow-xs">
                短镜头(&lt;5s): <strong :class="result.fragmentationStats.isHighFragmentation ? 'text-rose-600 font-bold' : 'text-amber-600'">{{ result.fragmentationStats.shortShots }}</strong> 镜 ({{ (result.fragmentationStats.shortShotRatio * 100).toFixed(0) }}%)
              </span>
              <span class="bg-white/90 px-2 py-0.5 rounded border border-slate-200/80 shadow-xs">
                标准镜头(5~8s): <strong class="text-emerald-600 font-bold">{{ result.fragmentationStats.normalShots }}</strong> 镜
              </span>
            </div>
          </div>

          <!-- 核心内容切换 Tabs -->
          <div class="flex-1 overflow-hidden flex flex-col">
            <el-tabs v-model="activeTab" class="custom-tabs h-full flex flex-col px-5 pt-2">
              <!-- ========================================== -->
              <!-- Tab 1: 角色资产 (Characters) -->
              <!-- ========================================== -->
              <el-tab-pane name="characters" class="h-full">
                <template #label>
                  <span class="flex items-center gap-1.5">
                    <span>👤</span> 提取角色资产 ({{ result.characters?.length || 0 }})
                  </span>
                </template>

                <div class="h-[calc(78vh-180px)] overflow-y-auto custom-scrollbar pr-2 pb-6">
                  <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 mt-2">
                    <div
                      v-for="(char, idx) in result.characters"
                      :key="idx"
                      class="bg-white border border-gray-200 rounded-xl p-4 shadow-sm hover:shadow-md transition-all flex flex-col justify-between"
                    >
                      <div>
                        <!-- 头部名称与定位 -->
                        <div class="flex items-center justify-between mb-2">
                          <div class="flex items-center gap-2">
                            <span class="w-8 h-8 rounded-full bg-blue-100 text-blue-600 flex items-center justify-center font-bold text-sm">
                              {{ (char.canonicalName || char.name)?.charAt(0) || '角' }}
                            </span>
                            <div>
                              <el-input v-model="char.name" size="small" class="!w-28 font-bold" />
                              <div v-if="char.canonicalName && char.canonicalName !== char.name" class="text-[10px] text-emerald-600 font-bold">
                                正式名: {{ char.canonicalName }}
                              </div>
                            </div>
                          </div>
                          <div class="flex items-center gap-1">
                            <el-tag
                              size="small"
                              :type="char.identityStatus === 'CONFIRMED' ? 'success' : char.identityStatus === 'PARTIAL' ? 'warning' : 'danger'"
                              effect="light"
                              class="text-[10px]"
                            >
                              {{ char.identityStatus === 'CONFIRMED' ? '已定名' : char.identityStatus === 'PARTIAL' ? '临时称谓' : '待消歧' }}
                            </el-tag>
                            <DictSelect
                              v-model="char.roleType"
                              dict-type="res_role_type"
                              size="small"
                              class="!w-20"
                            />
                          </div>
                        </div>

                        <!-- 别名列表 -->
                        <div v-if="char.aliases && char.aliases.length > 0" class="flex items-center gap-1 flex-wrap mb-1.5">
                          <span class="text-[10px] text-gray-400">别名:</span>
                          <el-tag
                            v-for="(al, aIdx) in char.aliases.slice(0, 3)"
                            :key="aIdx"
                            size="small"
                            type="info"
                            class="text-[10px] !h-4 !px-1"
                          >
                            {{ al }}
                          </el-tag>
                        </div>

                        <!-- 属性行 -->
                        <div class="flex items-center gap-2 mb-2 text-xs text-gray-500">
                          <DictSelect
                            v-model="char.gender"
                            dict-type="res_gender"
                            size="small"
                            class="!w-24"
                          />
                          <el-input v-model="char.personality" size="small" placeholder="性格人设简述" class="flex-1" />
                        </div>

                        <!-- 中文外貌与常服描述 (原著视觉 SSOT) -->
                        <div class="space-y-1 mb-2">
                          <span class="text-[11px] font-bold text-slate-700 flex items-center justify-between">
                            <span>👤 中文外貌与常服描述:</span>
                            <span class="text-[10px] text-slate-400">原著视觉 SSOT</span>
                          </span>
                          <el-input
                            v-model="char.appearanceDesc"
                            type="textarea"
                            :rows="2"
                            size="small"
                            placeholder="如: 24岁青年，面容清秀但下颌坚毅，黑色微乱碎发，身着深灰工装衬衫..."
                            class="text-xs"
                          />
                        </div>

                        <!-- 服饰装扮 Prompt -->
                        <div class="space-y-1.5 mb-2">
                          <span class="text-[11px] font-bold text-gray-600">👗 默认服饰 Prompt:</span>
                          <el-input
                            v-model="char.outfitPrompt"
                            type="textarea"
                            :rows="2"
                            size="small"
                            placeholder="如: wearing tailored suit..."
                            class="font-mono text-xs"
                          />
                        </div>

                        <!-- 触发词与证据 -->
                        <div class="flex items-center gap-2 text-xs mb-1">
                          <span class="text-gray-500 flex-shrink-0">🏷️ 触发词:</span>
                          <el-input v-model="char.triggerWords" size="small" placeholder="如: hero_linchen" class="flex-1 font-mono" />
                        </div>

                        <div v-if="char.evidenceText" class="text-[10px] text-gray-500 bg-amber-50/80 border border-amber-100 p-1.5 rounded line-clamp-1" :title="char.evidenceText">
                          📌 证据: {{ char.evidenceText }}
                        </div>
                      </div>

                      <!-- 底部资产复用与消歧状态 -->
                      <div class="mt-3 pt-2 border-t border-gray-100 flex items-center justify-between text-xs">
                        <span v-if="char.matchedCharacterId || char.existingCharacterId" class="text-emerald-600 flex items-center gap-1 font-mono">
                          <span>🔗</span> 已匹配现有角色 (ID: {{ char.matchedCharacterId || char.existingCharacterId }})
                          <span v-if="char.confidence" class="text-[10px] text-gray-400 font-mono">[{{ (char.confidence * 100).toFixed(0) }}%]</span>
                        </span>
                        <span v-else-if="char.identityStatus === 'UNRESOLVED'" class="text-amber-600 flex items-center gap-1">
                          <span>⚠️</span> 存在歧义候选，需确认
                        </span>
                        <span v-else class="text-blue-600 flex items-center gap-1">
                          <span>+</span> 将注册为新角色实体
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              </el-tab-pane>

              <!-- ========================================== -->
              <!-- Tab 2: 场景资产 (Scenes) -->
              <!-- ========================================== -->
              <el-tab-pane name="scenes" class="h-full">
                <template #label>
                  <span class="flex items-center gap-1.5">
                    <span>🏞️</span> 提取环境场景 ({{ result.scenes?.length || 0 }})
                  </span>
                </template>

                <div class="h-[calc(78vh-180px)] overflow-y-auto custom-scrollbar pr-2 pb-6">
                  <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 mt-2">
                    <div
                      v-for="(sc, idx) in result.scenes"
                      :key="idx"
                      class="bg-white border border-gray-200 rounded-xl p-4 shadow-sm hover:shadow-md transition-all flex flex-col justify-between"
                    >
                      <div>
                        <!-- 头部名称与空间类型 -->
                        <div class="flex items-center justify-between mb-2">
                          <el-input v-model="sc.sceneName" size="small" class="!w-44 font-bold" />
                          <DictSelect
                            v-model="sc.sceneType"
                            dict-type="res_scene_type"
                            size="small"
                            class="!w-24"
                          />
                        </div>

                        <!-- 时段与天气 -->
                        <div class="grid grid-cols-2 gap-2 mb-2">
                          <DictSelect
                            v-model="sc.timeOfDay"
                            dict-type="res_time_of_day"
                            size="small"
                            class="w-full"
                          />
                          <el-input v-model="sc.weatherAtmosphere" size="small" placeholder="天气/氛围 (如 SUNNY)" class="w-full" />
                        </div>

                        <!-- 中文场景描述 (原著视觉细节) -->
                        <div class="space-y-1.5 mb-2">
                          <span class="text-[11px] font-bold text-gray-700">📖 中文场景描述:</span>
                          <el-input
                            v-model="sc.description"
                            type="textarea"
                            :rows="2"
                            size="small"
                            placeholder="原著小说或大纲中的场景视觉细节、地理环境与陈设"
                          />
                        </div>

                        <!-- 空间环境与光影一体化描述 -->
                        <div class="space-y-1 mb-2">
                          <span class="text-[11px] font-bold text-gray-700">🏛️ 场景生图 Prompt (英文):</span>
                          <el-input
                            v-model="sc.scenePrompt"
                            type="textarea"
                            :rows="2"
                            size="small"
                            placeholder="如: dilapidated dim vintage room, peeling paint walls, warm amber flickering light..."
                            class="font-mono text-xs"
                          />
                        </div>

                        <!-- 地点补充说明 -->
                        <div class="flex items-center gap-2 text-xs">
                          <span class="text-gray-500 flex-shrink-0">📍 场景编号/地点:</span>
                          <span class="font-mono text-xs font-bold text-cyan-600 mr-1">{{ sc.id || ('SC' + (idx+1)) }}</span>
                          <el-input v-model="sc.locationName" size="small" placeholder="空间与关键道具细节" class="flex-1" />
                        </div>
                      </div>

                      <!-- 底部资产复用状态 -->
                      <div class="mt-3 pt-2 border-t border-gray-100 space-y-1.5">
                        <div class="flex items-center justify-between text-xs">
                          <span class="font-medium text-gray-600">复用已有场景资产</span>
                          <span v-if="sc.existingSceneId" class="text-emerald-600 font-mono">
                            ✓ 已绑定 (ID: {{ sc.existingSceneId }})
                          </span>
                          <span v-else class="text-blue-600">未绑定，可选择已有资产</span>
                        </div>
                        <el-select
                          v-model="sc.existingSceneId"
                          clearable
                          filterable
                          size="small"
                          class="w-full"
                          :loading="sceneOptionsLoading"
                          :disabled="sceneOptionsLoading || sceneOptions.length === 0"
                          placeholder="选择已有场景；留空时按名称匹配，否则新建"
                        >
                          <el-option
                            v-for="asset in sceneOptions"
                            :key="String(asset.id)"
                            :value="String(asset.id)"
                            :label="asset.name + ' · ' + (String(asset.dramaId) === '0' ? '公共场景' : '本短剧')"
                          />
                        </el-select>
                      </div>
                    </div>
                  </div>
                </div>
              </el-tab-pane>

              <!-- ========================================== -->
              <!-- Tab 3: 提取关键道具 (Props) -->
              <!-- ========================================== -->
              <el-tab-pane name="props" class="h-full">
                <template #label>
                  <span class="flex items-center gap-1.5">
                    <span>📦</span> 提取关键道具 ({{ result.props?.length || 0 }})
                  </span>
                </template>

                <div class="h-[calc(78vh-180px)] overflow-y-auto custom-scrollbar pr-2 pb-6">
                  <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 mt-2">
                    <div
                      v-for="(pr, idx) in result.props || []"
                      :key="idx"
                      class="bg-white border border-gray-200 rounded-xl p-4 shadow-sm hover:shadow-md transition-all flex flex-col justify-between"
                    >
                      <div>
                        <!-- 头部名称与道具类型 -->
                        <div class="flex items-center justify-between mb-2">
                          <div class="flex items-center gap-2">
                            <span class="text-xs font-mono font-bold text-purple-600">{{ pr.id || ('PR' + (idx+1)) }}</span>
                            <el-input v-model="pr.name" size="small" class="!w-36 font-bold" />
                          </div>
                          <span class="text-[11px] px-2 py-0.5 rounded bg-purple-50 text-purple-700 border border-purple-200 font-mono">
                            {{ pr.propType || 'KEY_PROP' }}
                          </span>
                        </div>

                        <!-- 道具视觉描述 Prompt -->
                        <div class="space-y-1.5 mb-2">
                          <span class="text-[11px] font-bold text-gray-600">🎨 道具视觉描述 Prompt (英文):</span>
                          <el-input
                            v-model="pr.propPrompt"
                            type="textarea"
                            :rows="2"
                            size="small"
                            placeholder="如: ornate antique brass desk clock with intricate engravings..."
                            class="font-mono text-xs"
                          />
                        </div>

                        <!-- 道具作用说明 -->
                        <div class="flex items-center gap-2 text-xs">
                          <span class="text-gray-500 flex-shrink-0">📝 道具作用:</span>
                          <el-input v-model="pr.description" size="small" placeholder="关键情节互动或视听细节" class="flex-1" />
                        </div>
                      </div>

                      <!-- 底部资产状态 -->
                      <div class="mt-3 pt-2 border-t border-gray-100 flex items-center justify-between text-xs">
                        <span v-if="pr.existingPropId" class="text-emerald-600 flex items-center gap-1 font-mono">
                          <span>✓</span> 已匹配现有道具 (ID: {{ pr.existingPropId }})
                        </span>
                        <span v-else class="text-blue-600 flex items-center gap-1">
                          <span>+</span> 将自动创建为新道具资产
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              </el-tab-pane>

              <!-- ========================================== -->
              <!-- Tab 3: 分集大纲与分镜台词流 (Storyboards) -->
              <!-- ========================================== -->
              <el-tab-pane name="storyboard" class="h-full">
                <template #label>
                  <span class="flex items-center gap-1.5">
                    <span>🎞️</span> 分集大纲与分镜台词流 ({{ result.episodes?.length || 0 }} 集)
                  </span>
                </template>

                <div class="h-[calc(78vh-180px)] flex gap-4 overflow-hidden pt-1 pb-4">
                  <!-- 左侧分集列表导航 (220px) -->
                  <div class="w-56 flex-shrink-0 bg-slate-50 border border-gray-200 rounded-xl p-3 flex flex-col gap-2 overflow-y-auto custom-scrollbar">
                    <div class="text-xs font-bold text-gray-500 uppercase tracking-wider mb-1 px-1">
                      分集导航 ({{ result.episodes?.length || 0 }})
                    </div>
                    <div
                      v-for="(ep, epIdx) in result.episodes"
                      :key="epIdx"
                      class="p-2.5 rounded-lg border cursor-pointer transition-all"
                      :class="selectedEpisodeIndex === epIdx ? 'bg-blue-50 border-blue-400 text-blue-700 shadow-sm' : 'bg-white border-gray-200 hover:border-gray-300 text-gray-700'"
                      @click="selectedEpisodeIndex = epIdx"
                    >
                      <div class="font-bold text-xs truncate">
                        {{ ep.title || `第 ${ep.episodeNo || (epIdx + 1)} 集` }}
                      </div>
                      <div class="flex items-center justify-between text-[11px] text-gray-400 mt-1">
                        <span>{{ ep.scenes?.length || 0 }} 场次</span>
                        <span class="font-mono">{{ ep.targetDuration || form.targetDurationPerEpisode || 300 }}s</span>
                      </div>
                    </div>
                  </div>

                  <!-- 右侧选中集的情景场次与分镜卡片流 -->
                  <div v-if="currentEpisode" class="flex-1 overflow-y-auto custom-scrollbar pr-2 space-y-4">
                    <!-- 单集标题与摘要条 -->
                    <div class="p-3 bg-blue-50/50 border border-blue-100 rounded-xl flex items-center justify-between gap-3">
                      <div class="flex items-center gap-2 flex-1">
                        <span class="text-base font-bold text-blue-900">📺</span>
                        <el-input v-model="currentEpisode.title" size="small" class="!w-60 font-bold" />
                        <el-input v-model="currentEpisode.summary" size="small" placeholder="本集剧情简介..." class="flex-1" />
                      </div>
                      <div class="text-xs text-gray-500 font-mono">
                        目标时长: {{ currentEpisode.targetDuration || form.targetDurationPerEpisode || 300 }}s
                      </div>
                    </div>

                    <!-- 循环场次 (Scenes) -->
                    <div
                      v-for="(sc, scIdx) in currentEpisode.scenes"
                      :key="scIdx"
                      class="bg-white border border-gray-200 rounded-xl p-4 shadow-sm"
                    >
                      <!-- 场次标题条 -->
                      <div class="flex items-center justify-between pb-3 mb-3 border-b border-gray-100">
                        <div class="flex items-center gap-2 flex-1">
                          <span class="px-2 py-0.5 rounded bg-indigo-50 text-indigo-700 font-mono text-xs font-bold">
                            场次 {{ sc.sceneNo || (scIdx + 1) }}
                          </span>
                          <el-input v-model="sc.sceneName" size="small" class="!w-52 font-bold" />
                          <el-input v-model="sc.summary" size="small" placeholder="场次摘要..." class="flex-1 text-xs" />
                        </div>
                        <el-tag size="small" type="info" class="font-mono">
                          {{ sc.shotGroups?.length ? `${sc.shotGroups.length} 镜头组 · ` : '' }}{{ getSceneTotalShots(sc) }} 镜
                        </el-tag>
                      </div>

                      <!-- 镜头组 (ShotGroups) 呈现 -->
                      <div v-if="sc.shotGroups?.length" class="space-y-4">
                        <div
                          v-for="(group, gIdx) in sc.shotGroups"
                          :key="gIdx"
                          class="bg-slate-50/90 border border-indigo-100 rounded-xl p-3.5 shadow-sm space-y-3"
                        >
                          <div class="flex items-center justify-between border-b border-indigo-50 pb-2">
                            <div class="flex items-center gap-2 flex-1">
                              <span class="text-xs font-mono font-bold bg-indigo-100 text-indigo-800 border border-indigo-200 px-2 py-0.5 rounded">
                                GROUP {{ group.groupNo || (gIdx + 1) }}
                              </span>
                              <el-input v-model="group.name" size="small" class="!w-44 font-bold" />
                              <el-input v-model="group.purpose" size="small" placeholder="连续叙事目的..." class="flex-1 text-xs" />
                            </div>
                            <span class="text-xs text-gray-400 font-mono">
                              {{ group.shots?.length || 0 }} 镜
                            </span>
                          </div>

                          <!-- 组内分镜卡片 -->
                          <div class="space-y-2.5">
                            <div
                              v-for="(shot, shotIdx) in group.shots"
                              :key="shotIdx"
                              class="bg-white border border-gray-200 rounded-lg p-3 hover:border-blue-300 hover:shadow-sm transition-all flex flex-col gap-2.5"
                            >
                              <!-- 镜头属性顶部栏 -->
                              <div class="flex flex-wrap items-center justify-between gap-2">
                                <div class="flex items-center gap-2">
                                  <span class="font-mono font-bold text-xs bg-slate-200 px-2 py-0.5 rounded text-slate-700">
                                    {{ shot.shotName || `S${String(currentEpisode.episodeNo || 1).padStart(2, '0')}-${String(shot.shotNo || (shotIdx + 1)).padStart(2, '0')}` }}
                                  </span>
                                  <DictSelect
                                    :model-value="shot.shotType ?? undefined"
                                    dict-type="shot_type"
                                    size="small"
                                    class="!w-32"
                                    @update:model-value="shot.shotType = $event ?? undefined"
                                    @change="shot.shotTypeLocked = isExplicitCameraChoice($event)"
                                  />
                                  <DictSelect
                                    :model-value="shot.cameraMovement ?? undefined"
                                    dict-type="camera_movement"
                                    size="small"
                                    class="!w-28"
                                    @update:model-value="shot.cameraMovement = $event ?? undefined"
                                    @change="shot.cameraMovementLocked = isExplicitCameraChoice($event)"
                                  />
                                  <div class="flex items-center gap-1 text-xs text-gray-500 font-mono">
                                    <span>⏱️</span>
                                    <el-input-number
                                      v-model="shot.duration"
                                      :min="0.5"
                                      :max="15"
                                      :step="0.5"
                                      :precision="1"
                                      size="small"
                                      class="!w-20"
                                      controls-position="right"
                                    />
                                    <span>s</span>
                                  </div>
                                </div>

                                <!-- 出场人物与焦点标签 -->
                                <div class="flex flex-wrap items-center gap-1.5">
                                  <el-tag
                                    v-if="shot.primaryCharacter"
                                    size="small"
                                    type="primary"
                                    effect="dark"
                                    class="!text-[11px] font-bold"
                                  >
                                    🎯 焦点: {{ shot.primaryCharacter }}
                                  </el-tag>
                                  <el-tag
                                    v-if="shot.secondaryCharacter"
                                    size="small"
                                    type="info"
                                    effect="plain"
                                    class="!text-[11px]"
                                  >
                                    👤 过肩: {{ shot.secondaryCharacter }}
                                  </el-tag>
                                  <span v-if="shot.characterNames?.length && !shot.primaryCharacter" class="text-[11px] text-gray-400">出场:</span>
                                  <el-tag
                                    v-for="(name, nIdx) in (shot.characterNames || []).filter((n: string) => n !== shot.primaryCharacter && n !== shot.secondaryCharacter)"
                                    :key="nIdx"
                                    size="small"
                                    type="success"
                                    effect="plain"
                                    class="!text-[11px]"
                                  >
                                    {{ name }}
                                  </el-tag>
                                </div>
                              </div>

                              <!-- 📜 镜头剧本 (核心文学台本 · 供后续AI结合原文生成Prompt) -->
                              <div class="bg-indigo-50/60 p-2.5 rounded-lg border border-indigo-100 text-xs space-y-1">
                                <div class="flex items-center justify-between text-indigo-900 font-bold mb-1">
                                  <span class="flex items-center gap-1.5">
                                    <span class="text-sm">📜</span>
                                    <span>镜头剧本 (Script / AI 参考台本):</span>
                                    <span class="text-[10px] font-mono text-indigo-500 font-normal">({{ (shot.scriptContent || '').length }} 字)</span>
                                  </span>
                                  <el-button
                                    v-if="shot.scriptContent"
                                    size="small"
                                    link
                                    type="primary"
                                    class="!text-[11px]"
                                    @click="copyPrompt(shot.scriptContent, '镜头剧本已复制')"
                                  >
                                    📋 复制剧本
                                  </el-button>
                                </div>
                                <el-input
                                  v-model="shot.scriptContent"
                                  type="textarea"
                                  :rows="3"
                                  size="small"
                                  placeholder="镜头剧本文学台本 (细致展开角色表演动作细节、神态心理外化、空间交互与戏剧推进，供后续AI精准推导生图与运镜Prompt)..."
                                  class="font-sans text-xs leading-relaxed"
                                />
                              </div>

                              <!-- 🎬 剧情动作描述 (纯中文) -->
                              <div class="flex items-start gap-2 text-xs">
                                <span class="text-gray-500 flex-shrink-0 mt-1 font-bold">🎬 剧情动作:</span>
                                <el-input
                                  v-model="shot.actionDescription"
                                  type="textarea"
                                  :rows="2"
                                  size="small"
                                  placeholder="中文动作与视觉叙事描述 (供创作者审阅、批注与时间轴追踪)..."
                                  class="flex-1"
                                />
                              </div>

                              <!-- 💬 角色台词对白气泡 -->
                              <div
                                v-if="shot.dialogue"
                                class="bg-amber-50/80 border border-amber-200 rounded-lg p-2.5 flex items-start gap-2 text-xs shadow-sm"
                              >
                                <span class="text-amber-800 font-bold flex-shrink-0 mt-0.5">💬 对白台词:</span>
                                <el-input
                                  v-model="shot.dialogue"
                                  type="textarea"
                                  :rows="1"
                                  autosize
                                  size="small"
                                  placeholder="对白台词内容..."
                                  class="flex-1 font-medium !text-amber-950"
                                />
                              </div>

                              <!-- 旁白独白 / 音效 -->
                              <div v-if="shot.voiceover || shot.soundEffect" class="grid grid-cols-1 md:grid-cols-2 gap-2 text-xs">
                                <div v-if="shot.voiceover" class="flex items-center gap-1.5 bg-purple-50/60 p-1.5 rounded border border-purple-100">
                                  <span class="text-purple-700 flex-shrink-0">💭 旁白:</span>
                                  <el-input v-model="shot.voiceover" size="small" class="flex-1" />
                                </div>
                                <div v-if="shot.soundEffect" class="flex items-center gap-1.5 bg-gray-100/70 p-1.5 rounded border border-gray-200">
                                  <span class="text-gray-600 flex-shrink-0">🔊 音效:</span>
                                  <el-input v-model="shot.soundEffect" size="small" class="flex-1" />
                                </div>
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>

                      <!-- 平铺兼容分镜卡片瀑布流 (Fallback Shots) -->
                      <div v-else class="space-y-3">
                        <div
                          v-for="(shot, shotIdx) in sc.shots"
                          :key="shotIdx"
                          class="bg-slate-50/80 border border-gray-200 rounded-xl p-3.5 hover:bg-white hover:border-blue-300 hover:shadow-md transition-all flex flex-col gap-2.5"
                        >
                          <!-- 镜头属性顶部栏 -->
                          <div class="flex flex-wrap items-center justify-between gap-2">
                            <div class="flex items-center gap-2">
                              <span class="font-mono font-bold text-xs bg-slate-200 px-2 py-0.5 rounded text-slate-700">
                                {{ shot.shotName || `S${String(currentEpisode.episodeNo || 1).padStart(2, '0')}-${String(shot.shotNo || (shotIdx + 1)).padStart(2, '0')}` }}
                              </span>
                              <DictSelect
                                :model-value="shot.shotType ?? undefined"
                                dict-type="shot_type"
                                size="small"
                                class="!w-32"
                                @update:model-value="shot.shotType = $event ?? undefined"
                                @change="shot.shotTypeLocked = isExplicitCameraChoice($event)"
                              />
                              <DictSelect
                                :model-value="shot.cameraMovement ?? undefined"
                                dict-type="camera_movement"
                                size="small"
                                class="!w-28"
                                @update:model-value="shot.cameraMovement = $event ?? undefined"
                                @change="shot.cameraMovementLocked = isExplicitCameraChoice($event)"
                              />
                              <div class="flex items-center gap-1 text-xs text-gray-500 font-mono">
                                <span>⏱️</span>
                                <el-input-number
                                  v-model="shot.duration"
                                  :min="0.5"
                                  :max="15"
                                  :step="0.5"
                                  :precision="1"
                                  size="small"
                                  class="!w-20"
                                  controls-position="right"
                                />
                                <span>s</span>
                              </div>
                            </div>

                            <!-- 出场人物与焦点标签 -->
                            <div class="flex flex-wrap items-center gap-1.5">
                              <el-tag
                                v-if="shot.primaryCharacter"
                                size="small"
                                type="primary"
                                effect="dark"
                                class="!text-[11px] font-bold"
                              >
                                🎯 焦点: {{ shot.primaryCharacter }}
                              </el-tag>
                              <el-tag
                                v-if="shot.secondaryCharacter"
                                size="small"
                                type="info"
                                effect="plain"
                                class="!text-[11px]"
                              >
                                👤 过肩: {{ shot.secondaryCharacter }}
                              </el-tag>
                              <span v-if="shot.characterNames?.length && !shot.primaryCharacter" class="text-[11px] text-gray-400">出场:</span>
                              <el-tag
                                v-for="(name, nIdx) in (shot.characterNames || []).filter((n: string) => n !== shot.primaryCharacter && n !== shot.secondaryCharacter)"
                                :key="nIdx"
                                size="small"
                                type="success"
                                effect="plain"
                                class="!text-[11px]"
                              >
                                {{ name }}
                              </el-tag>
                            </div>
                          </div>

                          <!-- 📜 镜头剧本 (核心文学台本 · 供后续AI结合原文生成Prompt) -->
                          <div class="bg-indigo-50/60 p-2.5 rounded-lg border border-indigo-100 text-xs space-y-1">
                            <div class="flex items-center justify-between text-indigo-900 font-bold mb-1">
                              <span class="flex items-center gap-1.5">
                                <span class="text-sm">📜</span>
                                <span>镜头剧本 (Script / AI 参考台本):</span>
                                <span class="text-[10px] font-mono text-indigo-500 font-normal">({{ (shot.scriptContent || '').length }} 字)</span>
                              </span>
                              <el-button
                                v-if="shot.scriptContent"
                                size="small"
                                link
                                type="primary"
                                class="!text-[11px]"
                                @click="copyPrompt(shot.scriptContent, '镜头剧本已复制')"
                              >
                                📋 复制剧本
                              </el-button>
                            </div>
                            <el-input
                              v-model="shot.scriptContent"
                              type="textarea"
                              :rows="3"
                              size="small"
                              placeholder="镜头剧本文学台本 (细致展开角色表演动作细节、神态心理外化、空间交互与戏剧推进，供后续AI精准推导生图与运镜Prompt)..."
                              class="font-sans text-xs leading-relaxed"
                            />
                          </div>

                          <!-- 🎬 剧情动作描述 (纯中文) -->
                          <div class="flex items-start gap-2 text-xs">
                            <span class="text-gray-500 flex-shrink-0 mt-1 font-bold">🎬 剧情动作:</span>
                            <el-input
                              v-model="shot.actionDescription"
                              type="textarea"
                              :rows="2"
                              size="small"
                              placeholder="中文动作与视觉叙事描述 (供创作者审阅、批注与时间轴追踪)..."
                              class="flex-1"
                            />
                          </div>

                          <!-- 💬 角色台词对白气泡 -->
                          <div
                            v-if="shot.dialogue"
                            class="bg-amber-50/80 border border-amber-200 rounded-lg p-2.5 flex items-start gap-2 text-xs shadow-sm"
                          >
                            <span class="text-amber-800 font-bold flex-shrink-0 mt-0.5">💬 对白台词:</span>
                            <el-input
                              v-model="shot.dialogue"
                              type="textarea"
                              :rows="1"
                              autosize
                              size="small"
                              placeholder="对白台词内容..."
                              class="flex-1 font-medium !text-amber-950"
                            />
                          </div>

                          <!-- 旁白独白 / 音效 -->
                          <div v-if="shot.voiceover || shot.soundEffect" class="grid grid-cols-1 md:grid-cols-2 gap-2 text-xs">
                            <div v-if="shot.voiceover" class="flex items-center gap-1.5 bg-purple-50/60 p-1.5 rounded border border-purple-100">
                              <span class="text-purple-700 flex-shrink-0">💭 旁白:</span>
                              <el-input v-model="shot.voiceover" size="small" class="flex-1" />
                            </div>
                            <div v-if="shot.soundEffect" class="flex items-center gap-1.5 bg-gray-100/70 p-1.5 rounded border border-gray-200">
                              <span class="text-gray-600 flex-shrink-0">🔊 音效:</span>
                              <el-input v-model="shot.soundEffect" size="small" class="flex-1" />
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </el-tab-pane>

              <!-- ========================================== -->
              <!-- Tab 4: 剧情分段 (Segments) -->
              <!-- ========================================== -->
              <el-tab-pane name="segments" class="h-full">
                <template #label>
                  <span class="flex items-center gap-1.5">
                    <span>📑</span> 剧情分段 ({{ result?.segments?.length || 0 }})
                  </span>
                </template>

                <div class="h-[calc(78vh-180px)] overflow-y-auto custom-scrollbar pr-2 pb-6">
                  <div class="grid grid-cols-1 md:grid-cols-2 gap-4 mt-2">
                    <div
                      v-for="seg in (result?.segments || [])"
                      :key="seg.id"
                      class="bg-white border rounded-xl p-4 shadow-sm hover:shadow-md transition-all flex flex-col justify-between"
                      :class="isSegmentFailed(seg.id) ? 'border-rose-300 bg-rose-50/20' : 'border-slate-200'"
                    >
                      <div>
                        <!-- 失败警告条与微调重试按钮 -->
                        <div v-if="isSegmentFailed(seg.id)" class="mb-3 p-2.5 bg-rose-50 border border-rose-200 rounded-lg flex items-center justify-between text-xs text-rose-700">
                          <div class="flex items-center gap-1.5 truncate mr-2">
                            <span class="font-bold flex-shrink-0">❌ 分镜生成受阻:</span>
                            <span class="truncate text-[11px] font-mono text-rose-600" :title="getSegmentErrorMessage(seg.id)">
                              {{ getSegmentErrorMessage(seg.id) }}
                            </span>
                          </div>
                          <el-button
                            size="small"
                            type="danger"
                            class="!text-xs flex-shrink-0 shadow-2xs font-semibold"
                            @click="openWorkerRetryDialog(seg)"
                          >
                            🛠️ 微调重试
                          </el-button>
                        </div>

                        <div class="flex items-center justify-between mb-2">
                          <div class="flex items-center gap-2">
                            <span
                              class="px-2 py-0.5 font-mono font-bold rounded text-xs"
                              :class="isSegmentFailed(seg.id) ? 'bg-rose-100 text-rose-700' : 'bg-blue-100 text-blue-700'"
                            >
                              {{ seg.id }}
                            </span>
                            <span class="font-bold text-slate-800 text-sm">{{ seg.title }}</span>
                            <el-tag
                              v-if="isSegmentFailed(seg.id)"
                              size="small"
                              type="danger"
                              class="text-[10px]"
                            >
                              待补救
                            </el-tag>
                            <el-tag
                              v-else
                              size="small"
                              type="success"
                              class="text-[10px]"
                            >
                              已就绪
                            </el-tag>
                          </div>
                          <span class="text-xs text-slate-500 font-mono">
                            {{ seg.startOffset }} ~ {{ seg.endOffset }} 字
                          </span>
                        </div>

                        <p class="text-xs text-slate-600 mb-3 bg-slate-50 p-2.5 rounded-lg border border-slate-100 leading-relaxed">
                          {{ seg.summary || '暂无剧情摘要' }}
                        </p>

                        <div class="flex flex-wrap gap-2 text-xs mb-2">
                          <div v-if="seg.characterIds?.length" class="flex items-center gap-1">
                            <span class="text-slate-400">👤 角色:</span>
                            <el-tag v-for="c in seg.characterIds" :key="c" size="small" type="info" class="text-[10px] font-medium">{{ formatCharacterName(c) }}</el-tag>
                          </div>
                          <div v-if="seg.locationIds?.length" class="flex items-center gap-1">
                            <span class="text-slate-400">🏞️ 场景:</span>
                            <el-tag v-for="l in seg.locationIds" :key="l" size="small" type="success" class="text-[10px] font-medium">{{ formatLocationName(l) }}</el-tag>
                          </div>
                        </div>

                        <div v-if="seg.narrativePurpose" class="text-[11px] text-indigo-700 bg-indigo-50/70 px-2.5 py-1 rounded border border-indigo-100/80 mb-2">
                          🎯 叙事意图: {{ seg.narrativePurpose }}
                        </div>
                      </div>

                      <div class="pt-2 mt-2 border-t border-slate-100 flex items-center justify-between text-xs">
                        <span class="text-slate-400 font-mono text-[11px]">Worker #{{ seg.sequence }}</span>
                        <el-button
                          size="small"
                          :type="isSegmentFailed(seg.id) ? 'danger' : 'default'"
                          link
                          class="!text-xs"
                          @click="openWorkerRetryDialog(seg)"
                        >
                          🛠️ {{ isSegmentFailed(seg.id) ? '立即修复该段' : '针对该段重新生成' }}
                        </el-button>
                      </div>
                    </div>
                  </div>
                </div>
              </el-tab-pane>
            </el-tabs>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部操作与入库控制栏 (固定在弹窗底部，100% 永久可见) -->
    <template #footer>
      <div v-if="result" class="flex flex-col gap-2 p-3 bg-gradient-to-r from-slate-50 via-indigo-50/40 to-blue-50/40 rounded-xl border border-indigo-100 shadow-sm mx-1 mb-1">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <!-- 归属与策略选择器 -->
          <div class="flex items-center gap-3 flex-wrap">
            <div class="flex items-center gap-1.5 text-xs font-bold text-slate-800">
              <span>🎯 入库归属:</span>
              <el-radio-group v-model="targetDramaType" size="small" @change="handleDramaTypeChange">
                <el-radio-button v-if="boundDrama" value="BOUND_DRAMA">
                  🎬 本短剧:《{{ boundDrama.title }}》
                </el-radio-button>
                <el-radio-button value="NEW_DRAMA">
                  ✨ 新建独立短剧
                </el-radio-button>
              </el-radio-group>
            </div>

            <!-- 当选择本短剧时展示集数策略 -->
            <div v-if="targetDramaType === 'BOUND_DRAMA' && boundDrama" class="flex items-center gap-1.5 text-xs font-bold text-slate-800 pl-3 border-l border-slate-300">
              <span>📥 剧集策略:</span>
              <el-radio-group v-model="commitMode" size="small" @change="handleCommitModeChange">
                <el-radio-button value="APPEND_TO_EPISODE">
                  ➕ 当前集追加 (顺延)
                </el-radio-button>
                <el-radio-button value="NEW_EPISODE">
                  🆕 新建下一集
                </el-radio-button>
                <el-radio-button value="OVERWRITE_EPISODE">
                  🔄 覆盖更新集
                </el-radio-button>
              </el-radio-group>

              <!-- 目标集数下拉/输入 -->
              <div class="flex items-center gap-1 ml-1 font-normal text-xs text-slate-600">
                <span>目标集:</span>
                <template v-if="commitMode === 'APPEND_TO_EPISODE' || commitMode === 'OVERWRITE_EPISODE'">
                  <el-select v-model="targetEpisodeNo" size="small" class="!w-32">
                    <el-option
                      v-for="ep in existingEpisodes"
                      :key="ep.episodeNo"
                      :label="ep.title || ('第' + ep.episodeNo + '集')"
                      :value="ep.episodeNo"
                    >
                      <div class="flex items-center justify-between">
                        <span>{{ ep.title || ('第' + ep.episodeNo + '集') }}</span>
                        <span class="text-[10px] text-gray-400">({{ ep.shotCount || 0 }} 镜)</span>
                      </div>
                    </el-option>
                    <el-option v-if="existingEpisodes.length === 0" :label="'第1集'" :value="1" />
                  </el-select>
                </template>
                <template v-else-if="commitMode === 'NEW_EPISODE'">
                  <span class="text-blue-700 font-bold">第</span>
                  <el-input-number v-model="targetEpisodeNo" :min="1" :max="999" size="small" class="!w-20" controls-position="right" />
                  <span class="text-blue-700 font-bold">集</span>
                </template>
              </div>
            </div>

            <!-- 当选择新建短剧时 -->
            <div v-else class="flex items-center gap-2 pl-3 border-l border-slate-300">
              <span class="text-xs font-semibold text-slate-700">新剧名称:</span>
              <el-input v-model="customDramaTitle" size="small" placeholder="留空则由 AI 提炼剧名" class="!w-44" />
            </div>
          </div>

          <!-- 右侧操作按钮组 -->
          <div class="flex items-center gap-2.5">
            <el-button @click="handleClosePanel">关闭</el-button>
            <el-button
              type="success"
              size="default"
              class="!font-bold shadow-md shadow-emerald-500/20"
              :loading="committing"
              @click="handleCommit"
            >
              {{ commitButtonLabel }}
            </el-button>
          </div>
        </div>

        <!-- 提示文案说明 -->
        <div class="text-[11px] text-slate-500 pt-1 border-t border-indigo-100/60 flex items-center justify-between">
          <span v-if="targetDramaType === 'BOUND_DRAMA' && boundDrama">
            <span v-if="commitMode === 'APPEND_TO_EPISODE'" class="text-emerald-700 font-medium">💡 原有历史分镜 100% 保留，本次新分镜将顺延追加在【第 {{ targetEpisodeNo }} 集】末尾。</span>
            <span v-else-if="commitMode === 'NEW_EPISODE'" class="text-blue-700 font-medium">💡 将作为独立的【第 {{ targetEpisodeNo }} 集】加入短剧，与已有剧集并列共存。</span>
            <span v-else-if="commitMode === 'OVERWRITE_EPISODE'" class="text-amber-700 font-medium">⚠️ 仅清空重写【第 {{ targetEpisodeNo }} 集】，其他剧集保持完好。</span>
          </span>
          <span v-else class="text-indigo-700 font-medium">
            ✨ 将以此剧本全新创建一部独立短剧，并初始化第 1 集。
          </span>
          <span class="text-[10px] text-gray-400">您可在上方审阅看板中修改角色/场景/分镜后入库</span>
        </div>
      </div>

      <!-- 未生成结果时的基础关闭栏 -->
      <div v-else-if="!isStreaming" class="flex items-center justify-end px-2">
        <el-button @click="handleClosePanel">关闭</el-button>
      </div>
    </template>
  </el-dialog>

  <!-- ======================================================== -->
  <!-- 拆解历史记录抽屉 (支持短剧与剧集强隔离与全览切换) -->
  <!-- ======================================================== -->
  <el-drawer
    v-model="historyDrawerVisible"
    title="📜 章节拆解任务历史记录"
    size="540px"
    destroy-on-close
    append-to-body
  >
    <div class="flex flex-col h-full">
      <!-- 头部剧集过滤隔离控制栏 -->
      <div class="pb-3 border-b border-gray-100 flex items-center justify-between flex-wrap gap-2">
        <div class="flex items-center gap-2">
          <span class="text-xs text-gray-500">过滤范围:</span>
          <el-radio-group v-model="historyScope" size="small" @change="loadHistory">
            <el-radio-button v-if="boundEpisodeId" value="CURRENT_EPISODE">
              🎯 当前集 (第 {{ currentEpisodeNo }} 集)
            </el-radio-button>
            <el-radio-button v-if="form.dramaId" value="DRAMA_HISTORY">
              📚 本剧历史
            </el-radio-button>
            <el-radio-button value="ALL_TASKS">
              🌐 全局历史
            </el-radio-button>
          </el-radio-group>
        </div>
        <el-button size="small" :loading="historyLoading" @click="loadHistory">
          🔄 刷新
        </el-button>
      </div>

      <!-- 历史任务列表 -->
      <div v-loading="historyLoading" class="flex-1 overflow-y-auto py-3 space-y-3 custom-scrollbar">
        <div v-if="historyList.length === 0" class="text-center py-12 text-gray-400 text-xs">
          暂无历史拆解任务记录
        </div>

        <div
          v-for="item in historyList"
          :key="item.taskId"
          class="bg-white border rounded-xl p-3.5 shadow-2xs hover:shadow-sm transition-all border-slate-200"
        >
          <div class="flex items-center justify-between mb-2">
            <div class="flex items-center gap-2">
              <span class="font-mono font-bold text-xs text-slate-800">#{{ item.taskId }}</span>
              <el-tag
                size="small"
                :type="item.status === 'SUCCESS' ? 'success' : item.status === 'PARTIAL_SUCCESS' ? 'warning' : item.status === 'FAILED' ? 'danger' : 'info'"
                class="text-[11px] font-medium"
              >
                {{ item.status === 'SUCCESS' ? '✓ 全部成功' : item.status === 'PARTIAL_SUCCESS' ? '⚠️ 部分成功' : item.status === 'FAILED' ? '❌ 失败' : '运行中' }}
              </el-tag>
              <el-tag v-if="item.episodeNo" size="small" type="info" class="text-[10px]">
                第 {{ item.episodeNo }} 集
              </el-tag>
            </div>
            <span class="text-[11px] text-gray-400 font-mono">{{ item.createTime || '-' }}</span>
          </div>

          <div class="text-xs font-bold text-slate-800 mb-1.5 truncate" :title="item.chapterTitle || item.dramaTitle">
            《{{ item.dramaTitle || '未命名短剧' }}》 {{ item.chapterTitle || '' }}
          </div>

          <div class="grid grid-cols-3 gap-2 bg-slate-50 rounded-lg p-2 mb-2.5 text-[11px] text-slate-600 font-mono">
            <div>
              <span class="text-slate-400">分段: </span>
              <span class="font-bold text-slate-700">{{ item.totalSegments }}段</span>
              <span v-if="item.failedSegments > 0" class="text-rose-600 font-bold ml-1">({{ item.failedSegments }}失败)</span>
            </div>
            <div>
              <span class="text-slate-400">镜头: </span>
              <span class="font-bold text-slate-700">{{ item.totalShots || 0 }}镜</span>
            </div>
            <div>
              <span class="text-slate-400">耗时: </span>
              <span>{{ item.durationSeconds ? item.durationSeconds + 's' : '-' }}</span>
            </div>
          </div>

          <div v-if="item.errorMessage" class="text-[11px] text-rose-600 bg-rose-50 p-2 rounded mb-2 font-mono">
            {{ item.errorMessage }}
          </div>

          <div class="flex items-center justify-between pt-1 border-t border-slate-100 text-xs">
            <span class="text-[11px] text-slate-400 font-mono truncate max-w-[180px]">{{ item.modelCode || 'AI' }}</span>
            <div class="flex items-center gap-2">
              <el-button
                size="small"
                type="primary"
                plain
                @click="handleLoadPreview(item)"
              >
                {{ item.status === 'RUNNING' || item.status === 'PENDING' ? '查看进度' : '📥 载入预览' }}
              </el-button>
              <el-popconfirm
                title="确定删除此条拆解历史记录吗？"
                :disabled="item.status === 'RUNNING' || item.status === 'PENDING' || item.status === 'RETRYING'"
                @confirm="handleDeleteTask(item.taskId)"
              >
                <template #reference>
                  <el-button size="small" type="danger" link :disabled="item.status === 'RUNNING' || item.status === 'PENDING' || item.status === 'RETRYING'">
                    删除
                  </el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>
        </div>
      </div>
    </div>
  </el-drawer>

  <!-- ======================================================== -->
  <!-- Worker 单段局部调优与重试弹窗 -->
  <!-- ======================================================== -->
  <el-dialog
    v-model="workerRetryDialogVisible"
    :title="`🛠️ 局部微调与单段重试 - 分段 [${retryForm.segmentId}] ${retryForm.segmentTitle}`"
    width="580px"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
  >
    <div class="space-y-3 py-1">
      <div class="bg-amber-50 border border-amber-200 rounded-lg p-2.5 text-xs text-amber-800 leading-relaxed">
        💡 <strong>调优指引：</strong>若大模型因包含血腥暴力或敏感描述而拒答，您可在下方直接修改/脱敏小说原文，或补充避险指导词（如“避免直接暴力描写，用光影与倒地反应表现”）。重试后系统将仅单独调用该段 AI，并自动调用 Java 本地合并引擎完成确定性重排！
      </div>

      <el-form label-position="top" size="small">
        <el-form-item label="小说分段原文微调 (可脱敏/修改血腥暴力描写)">
          <el-input
            v-model="retryForm.rawTextOverride"
            type="textarea"
            :rows="5"
            placeholder="请输入修改或脱敏后的小说分段文本..."
          />
        </el-form-item>

        <el-form-item label="特别指导与避险提示词 (Custom Instructions)">
          <el-input
            v-model="retryForm.customInstructions"
            type="textarea"
            :rows="2"
            placeholder="例如：请避开直接血腥描写，利用现场环境与人物反应表现紧张气氛，禁止输出残肢或大量鲜血细节"
          />
        </el-form-item>

        <div class="grid grid-cols-2 gap-2">
          <el-form-item label="重试 AI 提供商">
            <el-select v-model="retryForm.providerIdOverride" class="w-full" placeholder="默认保持原配置" clearable @change="handleRetryProviderChange">
              <el-option
                v-for="p in providerList"
                :key="p.id"
                :label="p.providerName"
                :value="p.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="重试 AI 模型">
            <el-select v-model="retryForm.modelCodeOverride" class="w-full" placeholder="默认保持原配置" clearable>
              <el-option
                v-for="m in retryModelList"
                :key="m.modelCode"
                :label="m.modelName || m.modelCode"
                :value="m.modelCode"
              />
            </el-select>
          </el-form-item>
        </div>
        <div class="rounded-lg border border-indigo-100 bg-indigo-50/40 p-2.5 space-y-2">
          <el-checkbox v-model="retrySkillOverrideEnabled">覆盖本次 Worker Skill 设置</el-checkbox>
          <div v-if="retrySkillOverrideEnabled" class="space-y-2">
            <el-switch v-model="retryDynamicSkills" size="small" active-text="允许按需调用" />
            <SkillSelector v-model="retryRequiredSkills" mode="API" :dynamic-enabled="retryDynamicSkills" />
          </div>
          <p v-else class="text-[11px] text-slate-500">沿用父任务的 Worker Skill 设置与版本快照。</p>
        </div>
      </el-form>
    </div>

    <template #footer>
      <div class="flex items-center justify-end gap-2">
        <el-button :disabled="workerRetryLoading" @click="workerRetryDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="workerRetryLoading"
          @click="handleExecuteWorkerRetry"
        >
          🚀 重新执行该段并合并
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, Close, Loading } from '@element-plus/icons-vue'
import DictSelect from '@/components/DictSelect.vue'
import DictTag from '@/components/DictTag.vue'
import SkillSelector from '@/components/SkillSelector.vue'
import { aiProviderApi } from '@/api/ai-provider'
import { scriptApi, tryExtractJson } from '@/api/script'
import { sceneApi } from '@/api/res-scene'
import { useTaskCenterStore } from '@/store/taskCenter'
import { dramaApi, episodeApi } from '@/api/drama'
import type { Drama, DramaEpisode } from '@/types/drama'
import type { ResSceneOption } from '@/types/resource'
import type { AiProviderVO, AiModel } from '@/types/ai-provider'
import type {
  ScriptDecomposeRequest,
  ScriptDecomposeResult,
  ScriptDecomposeCommit,
  DecomposedEpisode,
  DecomposedShot,
  ScriptSkillEvent
} from '@/types/script'

const emit = defineEmits<{
  (e: 'success', dramaId: number): void
  (e: 'panel-close'): void
}>()

const visible = ref(false)
const boundDrama = ref<Drama | null>(null)
const isStreaming = ref(false)
const startingInBackground = ref(false)
const skillEvents = ref<ScriptSkillEvent[]>([])
const plannerDynamicSkills = ref(true)
const workerDynamicSkills = ref(true)
const plannerRequiredSkills = ref<string[]>([])
const workerRequiredSkills = ref<string[]>([])
const streamOutputText = ref('')
const elapsedSeconds = ref(0)
const streamConsoleRef = ref<HTMLElement | null>(null)
const timer = ref<any>(null)
const abortController = ref<AbortController | null>(null)

function copyPrompt(text?: string, msg = '已复制到剪贴板') {
  if (!text) {
    ElMessage.warning('提示词内容为空')
    return
  }
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success(msg)
  }).catch(() => {
    ElMessage.error('复制失败，请手动选择复制')
  })
}

// 思考详情折叠状态与自动滚动
const showThinkingDetails = ref(false)
const autoScroll = ref(true)
const visibleSkillEvents = computed(() => result.value?.skillEvents?.length ? result.value.skillEvents : skillEvents.value)
const skillEventLabel = (event: ScriptSkillEvent) => {
  const target = event.segmentId || event.stage
  const source = event.source === 'REQUIRED' ? '指定加载' : 'AI 调用'
  const version = event.version ? ` v${event.version}` : ''
  return `[${target} · 第 ${event.attempt} 次] ${source} ${event.name}${version}: ${event.status}`
}

function isExplicitCameraChoice(value?: string | null): boolean {
  return Boolean(value && value.trim() && value.toUpperCase() !== 'AUTO')
}

function preserveExplicitCameraChoices(previous: ScriptDecomposeResult, updated: ScriptDecomposeResult) {
  const choicesByShot = new Map<string, { shotType?: string; cameraMovement?: string }>()
  const shotKey = (episodeNo: number, sceneNo: number, shot: DecomposedShot) =>
    JSON.stringify([episodeNo, sceneNo, shot.shotName, (shot.scriptContent || '').trim().replace(/\s+/g, ' ')])
  const visitShots = (result: ScriptDecomposeResult, visit: (key: string, shot: DecomposedShot) => void) => {
    for (const episode of result.episodes || []) {
      for (const scene of episode.scenes || []) {
        const shots = scene.shotGroups?.length
          ? scene.shotGroups.flatMap(group => group.shots || [])
          : (scene.shots || [])
        for (const shot of shots) visit(shotKey(episode.episodeNo, scene.sceneNo, shot), shot)
      }
    }
  }

  visitShots(previous, (key, shot) => {
    const choice: { shotType?: string; cameraMovement?: string } = {}
    if (shot.shotTypeLocked && isExplicitCameraChoice(shot.shotType)) choice.shotType = shot.shotType!
    if (shot.cameraMovementLocked && isExplicitCameraChoice(shot.cameraMovement)) choice.cameraMovement = shot.cameraMovement!
    if (choice.shotType || choice.cameraMovement) choicesByShot.set(key, choice)
  })

  visitShots(updated, (key, shot) => {
    const choice = choicesByShot.get(key)
    if (!choice) return
    if (choice.shotType) {
      shot.shotType = choice.shotType
      shot.shotTypeLocked = true
    }
    if (choice.cameraMovement) {
      shot.cameraMovement = choice.cameraMovement
      shot.cameraMovementLocked = true
    }
  })
}

interface ChannelLog {
  id: string
  name: string
  title?: string
  text: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  shotsCount?: number
}

const activeChannelTab = ref<string>('ALL')
const channelsMap = ref<Record<string, ChannelLog>>({
  ALL: { id: 'ALL', name: '📌 全部时序流水', text: '', status: 'RUNNING' },
  PLANNER: { id: 'PLANNER', name: '🎯 剧情分段', text: '', status: 'PENDING' }
})

interface DiscoveredAssets {
  characters?: any[]
  scenes?: any[]
  props?: any[]
}

const discoveredAssets = ref<DiscoveredAssets | null>(null)

const effectiveDiscoveredAssets = computed<DiscoveredAssets | null>(() => {
  if (discoveredAssets.value) return discoveredAssets.value
  if (result.value) {
    return {
      characters: result.value.characters,
      scenes: result.value.scenes,
      props: result.value.props
    }
  }
  return null
})

interface LiveSegmentItem {
  id: string
  sequence: number
  title?: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  shotsCount?: number
  errorMessage?: string | null
}

const liveSegmentsList = ref<LiveSegmentItem[]>([])

const currentThinkingPhase = computed(() => {
  const text = streamOutputText.value
  if (!text) return 1
  if (result.value) return 6
  if (text.includes('[5/5]') || text.includes('PromptBuilder') || text.includes('视听纯英文提示词装配完成')) return 5
  if (text.includes('[4/5]') || text.includes('[4/4]') || text.includes('ShotMerge Engine') || text.includes('防碎片') || text.includes('确定性合并')) return 4
  if (text.includes('[3/5]') || text.includes('[3/4]') || text.includes('WorkerPool') || text.includes('Worker SEG') || text.includes('分段并行')) return 3
  if (discoveredAssets.value != null || text.includes('[2/5]') || text.includes('[2/4]') || text.includes('场景·角色·道具') || text.includes('实体角色') || text.includes('消歧') || text.includes('资产提炼')) return 2
  return 1
})

const liveSegments = computed<LiveSegmentItem[]>(() => {
  if (liveSegmentsList.value.length > 0) {
    return liveSegmentsList.value
  }
  if (result.value?.segments && result.value.segments.length > 0) {
    return result.value.segments.map(s => {
      const segRes = result.value?.segmentResults?.find(r => r.segmentId === s.id)
      return {
        id: s.id,
        sequence: s.sequence || 1,
        title: s.title || s.id,
        status: (segRes?.status || (result.value?.status === 'PARTIAL_SUCCESS' ? 'SUCCESS' : 'SUCCESS')) as any,
        errorMessage: segRes?.errorMessage,
        shotsCount: undefined
      }
    })
  }
  return []
})

const channelList = computed(() => {
  const list: ChannelLog[] = []

  // 1. 全局时序流水
  list.push({
    id: 'ALL',
    name: '📌 全部时序流水',
    text: channelsMap.value.ALL?.text || streamOutputText.value || '',
    status: isStreaming.value ? 'RUNNING' : 'SUCCESS'
  })

  // 2. 剧情分段
  const plannerText = channelsMap.value.PLANNER?.text || ''
  list.push({
    id: 'PLANNER',
    name: '🎯 剧情分段',
    text: plannerText,
    status: currentThinkingPhase.value === 1 ? 'RUNNING' : plannerText ? 'SUCCESS' : 'PENDING'
  })

  // 3. 各分段专属通道
  const seenIds = new Set<string>()
  for (const seg of liveSegments.value) {
    seenIds.add(seg.id)
    const existing = channelsMap.value[seg.id]
    list.push({
      id: seg.id,
      name: `🎬 ${seg.id}` + (seg.title ? ` (${seg.title})` : ''),
      title: seg.title,
      text: existing?.text || '',
      status: seg.status,
      shotsCount: seg.shotsCount || existing?.shotsCount
    })
  }

  for (const [key, ch] of Object.entries(channelsMap.value)) {
    if (key.startsWith('SEG') && !seenIds.has(key)) {
      seenIds.add(key)
      list.push({
        id: key,
        name: ch.name || `🎬 ${key}`,
        title: ch.title,
        text: ch.text,
        status: ch.status,
        shotsCount: ch.shotsCount
      })
    }
  }

  return list
})

const currentActiveTabText = computed(() => {
  if (activeChannelTab.value === 'ALL') {
    return streamOutputText.value || '（正在连接大模型流式端点...）'
  }
  return channelsMap.value[activeChannelTab.value]?.text || '（该分段正在等待 Worker 线程启动或暂无独立输出...）'
})

const committing = ref(false)
const activeTab = ref('storyboard')
const selectedEpisodeIndex = ref(0)

// 文件上传与章节解析状态
const uploadedFile = ref<{ name: string; size: string; rawContent: string } | null>(null)
const detectedChapters = ref<{ title: string; content: string }[]>([])
const selectedChapterIndex = ref<number>(-1)

// 模型与提供商列表
const providerList = ref<AiProviderVO[]>([])
const modelList = ref<AiModel[]>([])
const providersLoading = ref(false)
const modelsLoading = ref(false)

// 视觉基调快捷预设
const stylePresets = [
  { label: '🌌 日漫高质量深夜档', prompt: '日本高质量深夜档动画截图，顶级剧场版动画分镜，作画监督级利落线条，精致清晰的面部作画，现代动漫摄影合成，冷灰蓝电影调色，锐利的戏剧化侧面光影，角色边缘冷光勾勒，通透呼吸感，冷峻严肃的悬疑张力，画面清晰可辨，高完成度' },
  { label: '🎬 现代都市微悬疑', prompt: '现代都市微悬疑，冷色调，浅景深，高对比度质感，局部点光源' },
  { label: '🏮 国风东方玄幻', prompt: '国风东方玄幻，厚重视觉质感，古朴金木材质，丁达尔漫射光，暗调神秘' },
  { label: '🌆 90年代复古港风', prompt: '90年代复古港风，暖黄低饱和色调，百叶窗阴影，强烈光影对比与浅景深' },
  { label: '⚡ 赛博朋克霓虹', prompt: '赛博朋克电影质感，高反差冷青色与洋红撞色，雨夜霓虹漫射反射，迷雾光晕' },
  { label: '🌸 唯美日系水彩漫感', prompt: '唯美日系动漫光影，通透水彩纯净感，温暖高调漫射光，细腻天空与微光云层' }
]

// 表单入参
const form = reactive<ScriptDecomposeRequest>({
  providerId: undefined as any,
  modelCode: '',
  rawText: '',
  dramaId: undefined,
  chapterTitle: '',
  startEpisodeNo: 1,
  targetEpisodes: undefined,
  targetDurationPerEpisode: 300,
  stylePreset: 'cinematic-realism',
  styleTone: '',
  aspectRatio: '9:16',
  pacingPreset: 'STANDARD'
})

// 已有剧集与入库策略状态 (用户自主选择)
const targetDramaType = ref<'BOUND_DRAMA' | 'NEW_DRAMA'>('BOUND_DRAMA')
const customDramaTitle = ref('')
const existingEpisodes = ref<DramaEpisode[]>([])
const sceneOptions = ref<ResSceneOption[]>([])
const sceneOptionsLoading = ref(false)
let sceneOptionsRequestVersion = 0
const commitMode = ref<'APPEND_TO_EPISODE' | 'NEW_EPISODE' | 'OVERWRITE_EPISODE' | 'REPLACE_ALL'>('APPEND_TO_EPISODE')
const targetEpisodeNo = ref<number>(1)

async function handleDramaTypeChange(val: any) {
  if (val === 'BOUND_DRAMA') {
    form.dramaId = boundDrama.value?.id
    if (existingEpisodes.value.length > 0) {
      commitMode.value = 'APPEND_TO_EPISODE'
      targetEpisodeNo.value = existingEpisodes.value[0].episodeNo || 1
    } else {
      commitMode.value = 'NEW_EPISODE'
      targetEpisodeNo.value = 1
    }
  } else {
    form.dramaId = undefined
    commitMode.value = 'NEW_EPISODE'
    targetEpisodeNo.value = 1
  }
  await loadSceneOptions(form.dramaId)
}

async function loadSceneOptions(dramaId?: string | number | null) {
  const requestVersion = ++sceneOptionsRequestVersion
  if (dramaId === undefined || dramaId === null || String(dramaId).trim() === '') {
    sceneOptions.value = []
    sceneOptionsLoading.value = false
    return
  }
  sceneOptionsLoading.value = true
  try {
    const options = (await sceneApi.getOptions(dramaId)) || []
    if (requestVersion === sceneOptionsRequestVersion) sceneOptions.value = options
  } catch (error) {
    if (requestVersion === sceneOptionsRequestVersion) {
      sceneOptions.value = []
      console.warn('获取已有场景资产失败', error)
    }
  } finally {
    if (requestVersion === sceneOptionsRequestVersion) sceneOptionsLoading.value = false
  }
}

function handleCommitModeChange(val: any) {
  if (val === 'APPEND_TO_EPISODE') {
    targetEpisodeNo.value = existingEpisodes.value[0]?.episodeNo || 1
  } else if (val === 'NEW_EPISODE') {
    const maxEp = existingEpisodes.value.length > 0
      ? Math.max(...existingEpisodes.value.map(e => e.episodeNo || 1))
      : 0
    targetEpisodeNo.value = maxEp + 1
  } else if (val === 'OVERWRITE_EPISODE') {
    targetEpisodeNo.value = existingEpisodes.value[0]?.episodeNo || 1
  }
}

function formatCharacterName(c: string): string {
  if (!c) return ''
  if (result.value?.characters) {
    const found = result.value.characters.find(ch => ch.name === c || ch.canonicalName === c || ch.displayName === c)
    if (found) return found.canonicalName || found.name || found.displayName || c
  }
  return c
}

function formatLocationName(l: string): string {
  if (!l) return ''
  if (result.value?.scenes) {
    const found = result.value.scenes.find(sc => sc.sceneName === l || sc.locationName === l)
    if (found) return found.sceneName || found.locationName || l
  }
  return l
}

const commitButtonLabel = computed(() => {
  if (targetDramaType.value === 'NEW_DRAMA' || !boundDrama.value) {
    const title = customDramaTitle.value || result.value?.dramaTitle || form.chapterTitle || '新短剧'
    return `💾 创建全新短剧《${title}》并入库`
  }
  if (commitMode.value === 'APPEND_TO_EPISODE') {
    return `💾 追加写入《${boundDrama.value.title}》第 ${targetEpisodeNo.value} 集`
  }
  if (commitMode.value === 'NEW_EPISODE') {
    return `💾 新建《${boundDrama.value.title}》第 ${targetEpisodeNo.value} 集并入库`
  }
  if (commitMode.value === 'OVERWRITE_EPISODE') {
    return `💾 覆盖重写《${boundDrama.value.title}》第 ${targetEpisodeNo.value} 集`
  }
  return `💾 提交入库`
})

function parseChapterNumber(title: string): number | null {
  if (!title) return null
  const match = title.match(/第\s*([0-9一二三四五六七八九十百千]+)\s*[章回集卷节]/)
  if (!match) return null
  const numStr = match[1].trim()
  if (/^\d+$/.test(numStr)) {
    return parseInt(numStr, 10)
  }
  const chMap: Record<string, number> = {
    '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10,
    '十一': 11, '十二': 12, '十三': 13, '十四': 14, '十五': 15, '十六': 16, '十七': 17, '十八': 18, '十九': 19, '二十': 20
  }
  if (chMap[numStr] !== undefined) return chMap[numStr]
  if (numStr.startsWith('十') && numStr.length === 2 && chMap[numStr[1]]) {
    return 10 + chMap[numStr[1]]
  }
  if (numStr.endsWith('十') && numStr.length === 2 && chMap[numStr[0]]) {
    return chMap[numStr[0]] * 10
  }
  return null
}

// 拆解结果
const result = ref<ScriptDecomposeResult | null>(null)

const rawTextLength = computed(() => form.rawText?.length || 0)

const textLengthStatus = computed(() => {
  const len = rawTextLength.value
  if (len === 0) {
    return { type: 'empty', label: '待输入', tip: '', badgeClass: 'text-gray-400' }
  }
  if (len <= 15000) {
    return {
      type: 'optimal',
      label: '篇幅极佳 (建议1~3集)',
      tip: '当前文本篇幅适中，大模型可生成极细腻的高质量逐镜分镜与生动角色对白。',
      badgeClass: 'bg-emerald-50 text-emerald-700 border-emerald-300'
    }
  }
  if (len <= 35000) {
    return {
      type: 'warning',
      label: '篇幅偏长 (约4~8集)',
      tip: '文本较长，AI 将提炼精简核心剧情。若需更细致的分镜对白，建议在上方选择器按章节分次拆解。',
      badgeClass: 'bg-amber-50 text-amber-700 border-amber-300'
    }
  }
  return {
    type: 'exceeded',
    label: '超出单次拆解上限 (>3.5万字)',
    tip: '单次拆解建议在 3.5 万字以内以防大模型 Token 溢出与剧情遗漏。请在上方的【识别章节选择器】中选择单章（如第1~3章）进行拆解。',
    badgeClass: 'bg-rose-50 text-rose-700 border-rose-300 animate-pulse'
  }
})

const canDecompose = computed(() => {
  const len = rawTextLength.value
  return Boolean(form.providerId && form.modelCode && len > 0 && len <= 35000)
})

const currentEpisode = computed<DecomposedEpisode | null>(() => {
  if (!result.value?.episodes || result.value.episodes.length === 0) return null
  return result.value.episodes[selectedEpisodeIndex.value] || null
})

function getSceneTotalShots(sc: any): number {
  if (sc.shotGroups?.length) {
    return sc.shotGroups.reduce((acc: number, g: any) => acc + (g.shots?.length || 0), 0)
  }
  return sc.shots?.length || 0
}

const totalShotsCount = computed(() => {
  if (!result.value?.episodes) return 0
  let count = 0
  for (const ep of result.value.episodes) {
    if (ep.scenes) {
      for (const sc of ep.scenes) {
        count += getSceneTotalShots(sc)
      }
    }
  }
  return count
})

onMounted(() => {
  loadProviders()
})

/**
 * 彻底重置剧本拆解的所有工作区状态（清空上一轮的输入、过程卡片流与结果数据）
 */
function resetDecomposeState() {
  sceneOptionsRequestVersion++
  sceneOptions.value = []
  sceneOptionsLoading.value = false
  // 1. 中止可能仍在进行的流式后台任务与定时器
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
  if (timer.value) {
    clearInterval(timer.value)
    timer.value = null
  }
  isStreaming.value = false
  elapsedSeconds.value = 0

  // 2. 清空文本输入与文件解析状态（根治上一次文本与章节残留）
  form.rawText = ''
  form.chapterTitle = ''
  form.styleTone = ''
  plannerDynamicSkills.value = true
  workerDynamicSkills.value = true
  plannerRequiredSkills.value = []
  workerRequiredSkills.value = []
  uploadedFile.value = null
  detectedChapters.value = []
  selectedChapterIndex.value = -1

  // 3. 清空流式思考过程与 Worker 状态矩阵（根治上一轮卡片流残留）
  streamOutputText.value = ''
  skillEvents.value = []
  showThinkingDetails.value = false
  liveSegmentsList.value = []
  activeChannelTab.value = 'ALL'
  channelsMap.value = {
    ALL: { id: 'ALL', name: '📌 全部时序流水', text: '', status: 'RUNNING' },
    PLANNER: { id: 'PLANNER', name: '🎯 剧情分段', text: '', status: 'PENDING' }
  }

  // 4. 清空拆解结果与入库草稿状态
  result.value = null
  customDramaTitle.value = ''
  selectedEpisodeIndex.value = 0
  activeTab.value = 'storyboard'
  committing.value = false

  // 5. 重置剧集隔离与历史/重试状态
  boundEpisodeId.value = null
  currentEpisodeNo.value = null
  historyDrawerVisible.value = false
  workerRetryDialogVisible.value = false
}

// ========================================================
// 拆解历史记录与剧集强隔离状态
// ========================================================
const historyDrawerVisible = ref(false)
const historyLoading = ref(false)
const historyList = ref<import('@/types/script').ChapterDecomposeHistory[]>([])
const historyScope = ref<'CURRENT_EPISODE' | 'DRAMA_HISTORY' | 'ALL_TASKS'>('ALL_TASKS')
const boundEpisodeId = ref<number | null>(null)
const currentEpisodeNo = ref<number | null>(null)

async function openHistoryDrawer() {
  historyDrawerVisible.value = true
  if (boundEpisodeId.value) {
    historyScope.value = 'CURRENT_EPISODE'
  } else if (form.dramaId) {
    historyScope.value = 'DRAMA_HISTORY'
  } else {
    historyScope.value = 'ALL_TASKS'
  }
  await loadHistory()
}

async function loadHistory() {
  historyLoading.value = true
  try {
    let dramaIdParam: number | undefined = undefined
    let episodeIdParam: number | undefined = undefined

    if (historyScope.value === 'CURRENT_EPISODE' && boundEpisodeId.value) {
      dramaIdParam = form.dramaId || undefined
      episodeIdParam = boundEpisodeId.value
    } else if (historyScope.value === 'DRAMA_HISTORY' && form.dramaId) {
      dramaIdParam = form.dramaId
    } // ALL_TASKS: both dramaIdParam and episodeIdParam remain undefined to query global recent history

    const res = await scriptApi.getHistory(dramaIdParam, episodeIdParam)
    historyList.value = res || []
  } catch (e: any) {
    ElMessage.error(e.message || '获取拆解历史记录失败')
  } finally {
    historyLoading.value = false
  }
}

async function handleLoadPreview(item: import('@/types/script').ChapterDecomposeHistory) {
  if (item.status === 'RUNNING' || item.status === 'PENDING') {
    historyDrawerVisible.value = false
    await handleStartDecompose(String(item.taskId))
    return
  }
  try {
    const preview = await scriptApi.getTaskPreview(item.taskId)
    if (preview) {
      result.value = preview
      discoveredAssets.value = {
        characters: preview.characters || [],
        scenes: preview.scenes || [],
        props: preview.props || []
      }
      if (item.chapterTitle) {
        form.chapterTitle = item.chapterTitle
      }
      if (item.dramaTitle) {
        customDramaTitle.value = item.dramaTitle
      }
      selectedEpisodeIndex.value = 0
      historyDrawerVisible.value = false
      activeTab.value = 'storyboard'
      ElMessage.success(`已成功载入任务 #${item.taskId} 的拆解预览大纲`)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '载入预览大纲失败')
  }
}

async function handleDeleteTask(taskId: string | number) {
  try {
    await scriptApi.deleteTask(taskId)
    ElMessage.success('已删除该任务记录')
    await loadHistory()
  } catch (e: any) {
    ElMessage.error(e.message || '删除任务失败')
  }
}

// ========================================================
// Worker 单段局部调优与重试状态
// ========================================================
const workerRetryDialogVisible = ref(false)
const workerRetryLoading = ref(false)
const retrySkillOverrideEnabled = ref(false)
const retryDynamicSkills = ref(false)
const retryRequiredSkills = ref<string[]>([])
const retryModelList = ref<AiModel[]>([])
const retryForm = reactive({
  segmentId: '',
  segmentTitle: '',
  rawTextOverride: '',
  customInstructions: '',
  providerIdOverride: undefined as number | undefined,
  modelCodeOverride: undefined as string | undefined
})

function isSegmentFailed(segId: string): boolean {
  if (!result.value) return false
  const segRes = result.value.segmentResults?.find(r => r.segmentId === segId)
  return segRes?.status === 'FAILED'
}

function getSegmentErrorMessage(segId: string): string {
  if (!result.value) return ''
  const segRes = result.value.segmentResults?.find(r => r.segmentId === segId)
  return segRes?.errorMessage || '大模型解析未生成有效分镜'
}

const hasFailedSegments = computed(() => {
  return result.value?.segmentResults?.some(r => r.status === 'FAILED') || false
})

function openWorkerRetryDialog(seg: any) {
  if (!result.value?.taskId) {
    ElMessage.warning('当前结果未关联任务记录，无法进行单段微调重试')
    return
  }
  retryForm.segmentId = seg.id
  retrySkillOverrideEnabled.value = false
  retryDynamicSkills.value = workerDynamicSkills.value
  retryRequiredSkills.value = [...workerRequiredSkills.value]
  retryForm.segmentTitle = seg.title || seg.id
  const storySeg = result.value.segments?.find(s => s.id === seg.id)
  retryForm.rawTextOverride = storySeg?.rawText || ''
  retryForm.customInstructions = '请避开直接血腥暴力描写，利用现场环境与人物反应表现紧张情绪，保留完整动作与剧情结果，严格按照 JSON 格式输出'
  retryForm.providerIdOverride = form.providerId
  retryForm.modelCodeOverride = form.modelCode
  retryModelList.value = modelList.value
  workerRetryDialogVisible.value = true
}

async function handleRetryProviderChange(pId?: number) {
  if (!pId) {
    retryModelList.value = modelList.value
    retryForm.modelCodeOverride = undefined
    return
  }
  try {
    const res = await aiProviderApi.getModelList(pId, 'CHAT')
    retryModelList.value = res || []
    if (res?.length) {
      retryForm.modelCodeOverride = res[0].modelCode
    }
  } catch {}
}

async function handleExecuteWorkerRetry() {
  if (!result.value?.taskId) return
  workerRetryLoading.value = true
  try {
    const updatedResult = await scriptApi.retryWorker(result.value.taskId, {
      segmentId: retryForm.segmentId,
      customInstructions: retryForm.customInstructions || undefined,
      rawTextOverride: retryForm.rawTextOverride || undefined,
      providerIdOverride: retryForm.providerIdOverride || undefined,
      modelCodeOverride: retryForm.modelCodeOverride || undefined,
      skillPolicyOverride: retrySkillOverrideEnabled.value ? {
        allowDynamicLoad: retryDynamicSkills.value,
        requiredSkillNames: [...retryRequiredSkills.value]
      } : undefined
    })
    if (updatedResult) {
      if (result.value) preserveExplicitCameraChoices(result.value, updatedResult)
      result.value = updatedResult
      discoveredAssets.value = {
        characters: updatedResult.characters || [],
        scenes: updatedResult.scenes || [],
        props: updatedResult.props || []
      }
      workerRetryDialogVisible.value = false
      ElMessage.success(`分段 ${retryForm.segmentId} 局部重试成功并已重新确定性合并！`)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '重试失败')
  } finally {
    workerRetryLoading.value = false
  }
}

function handleDialogClose() {
  resetDecomposeState()
  emit('panel-close')
}

function handleClosePanel() {
  visible.value = false
  resetDecomposeState()
  emit('panel-close')
}

async function open(target?: number | Drama, currentEpisodeId?: number | null) {
  // 打开前彻底清空上一轮的输入文本、分段卡片流与拆解结果
  resetDecomposeState()
  visible.value = true
  boundEpisodeId.value = currentEpisodeId || null
  form.episodeId = currentEpisodeId || null

  if (target) {
    targetDramaType.value = 'BOUND_DRAMA'
    if (typeof target === 'object') {
      boundDrama.value = target
      form.dramaId = target.id
      form.aspectRatio = target.aspectRatio || '9:16'
      form.stylePreset = target.stylePreset || 'cinematic-realism'
      form.styleTone = target.styleTone || ''
      form.targetEpisodes = target.targetEpisodes || undefined
      form.targetDurationPerEpisode = 300
    } else {
      form.dramaId = target
      form.targetDurationPerEpisode = 300
      try {
        const dramaDetail = await dramaApi.getById(target)
        if (dramaDetail) {
          boundDrama.value = dramaDetail
          form.aspectRatio = dramaDetail.aspectRatio || '9:16'
          form.stylePreset = dramaDetail.stylePreset || 'cinematic-realism'
          form.styleTone = dramaDetail.styleTone || ''
          form.targetEpisodes = dramaDetail.targetEpisodes || undefined
        }
      } catch (e: any) {
        console.warn('获取短剧详情失败', e)
      }
    }

    // 查询该短剧已有的剧集列表
    if (form.dramaId) {
      try {
        const epList = await episodeApi.getListByDramaId(form.dramaId)
        existingEpisodes.value = epList || []
        if (boundEpisodeId.value) {
          const matchEp = existingEpisodes.value.find(e => e.id === boundEpisodeId.value)
          if (matchEp) {
            currentEpisodeNo.value = matchEp.episodeNo
            targetEpisodeNo.value = matchEp.episodeNo
            form.startEpisodeNo = matchEp.episodeNo
            commitMode.value = 'APPEND_TO_EPISODE'
          }
        } else if (existingEpisodes.value.length > 0) {
          commitMode.value = 'APPEND_TO_EPISODE'
          targetEpisodeNo.value = existingEpisodes.value[0].episodeNo || 1
          form.startEpisodeNo = 1
        } else {
          commitMode.value = 'NEW_EPISODE'
          targetEpisodeNo.value = 1
          form.startEpisodeNo = 1
        }
      } catch (e) {
        console.warn('获取短剧剧集列表失败', e)
        existingEpisodes.value = []
        commitMode.value = 'NEW_EPISODE'
      }
    }
  } else {
    targetDramaType.value = 'NEW_DRAMA'
    boundDrama.value = null
    existingEpisodes.value = []
    commitMode.value = 'NEW_EPISODE'
    targetEpisodeNo.value = 1
    form.dramaId = undefined
    form.aspectRatio = '9:16'
    form.stylePreset = 'cinematic-realism'
    form.styleTone = ''
    form.targetEpisodes = undefined
    form.targetDurationPerEpisode = 300
    form.pacingPreset = 'STANDARD'
    form.startEpisodeNo = 1
  }

  await loadSceneOptions(form.dramaId)

  if (providerList.value.length === 0) {
    await loadProviders()
  }
}

async function loadProviders() {
  providersLoading.value = true
  try {
    const res = await aiProviderApi.getListEnabled()
    providerList.value = res || []
    // 保持未选择状态，由用户自主选择提供商与模型
  } catch (e: any) {
    ElMessage.error(e.message || '加载 AI 提供商列表失败')
  } finally {
    providersLoading.value = false
  }
}

async function handleProviderChange(providerId: number) {
  form.modelCode = ''
  modelList.value = []
  if (providerId) {
    await loadModels(providerId)
  }
}

async function loadModels(providerId: number) {
  if (!providerId) {
    modelList.value = []
    form.modelCode = ''
    return
  }
  modelsLoading.value = true
  try {
    const res = await aiProviderApi.getModelList(providerId, 'CHAT')
    modelList.value = res || []
    if (form.modelCode && !modelList.value.some(m => m.modelCode === form.modelCode)) {
      form.modelCode = ''
    }
  } catch (e: any) {
    ElMessage.error(e.message || '加载 AI 模型列表失败')
  } finally {
    modelsLoading.value = false
  }
}

async function handleFileChange(uploadFile: any) {
  const rawFile = uploadFile.raw as File
  if (!rawFile) return

  if (rawFile.size > 2 * 1024 * 1024) {
    ElMessage.warning('上传文件大小不能超过 2MB（约 70 万字）')
    return
  }

  try {
    const text = await readTextFile(rawFile)
    if (!text || text.trim().length === 0) {
      ElMessage.warning('上传的文件内容为空')
      return
    }

    const sizeStr = formatFileSize(rawFile.size)
    uploadedFile.value = {
      name: rawFile.name,
      size: sizeStr,
      rawContent: text
    }

    // 智能章节提取
    extractChapters(text)
    selectedChapterIndex.value = -1
    form.rawText = text

    ElMessage.success(`成功导入《${rawFile.name}》（${text.length} 字）`)
  } catch (e: any) {
    ElMessage.error('解析文件失败: ' + (e.message || '格式不兼容'))
  }
}

function readTextFile(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = (e) => {
      const buffer = e.target?.result as ArrayBuffer
      // 优先使用 UTF-8 严格解码，遇乱码回退到 GBK
      try {
        const utf8Decoder = new TextDecoder('utf-8', { fatal: true })
        resolve(utf8Decoder.decode(buffer))
      } catch {
        try {
          const gbkDecoder = new TextDecoder('gbk')
          resolve(gbkDecoder.decode(buffer))
        } catch (err) {
          reject(err)
        }
      }
    }
    reader.onerror = reject
    reader.readAsArrayBuffer(file)
  })
}

function extractChapters(text: string) {
  detectedChapters.value = []
  // 匹配常见的第X章/第X回/第X集/第X节
  const chapterRegex = /(?:^|\n)\s*(第\s*[0-9一二三四五六七八九十百千]+\s*[章回集卷节][^\n\r]*)/g
  const matches: { title: string; index: number }[] = []
  let match: RegExpExecArray | null

  while ((match = chapterRegex.exec(text)) !== null) {
    matches.push({
      title: match[1].trim(),
      index: match.index
    })
  }

  if (matches.length >= 2) {
    for (let i = 0; i < matches.length; i++) {
      const start = matches[i].index
      const end = i + 1 < matches.length ? matches[i + 1].index : text.length
      const chapterContent = text.substring(start, end).trim()
      detectedChapters.value.push({
        title: matches[i].title,
        content: chapterContent
      })
    }
  }
}

function handleChapterSelect(index: number) {
  selectedChapterIndex.value = index
  if (index === -1) {
    form.rawText = uploadedFile.value?.rawContent || ''
    form.chapterTitle = ''
  } else if (detectedChapters.value[index]) {
    const ch = detectedChapters.value[index]
    form.rawText = ch.content
    form.chapterTitle = ch.title
    const extractedNum = parseChapterNumber(ch.title)
    if (extractedNum) {
      form.startEpisodeNo = extractedNum
      targetEpisodeNo.value = extractedNum
    }
  }
}

function handleRemoveFile() {
  uploadedFile.value = null
  detectedChapters.value = []
  selectedChapterIndex.value = -1
}

function handleClearText() {
  form.rawText = ''
  handleRemoveFile()
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function fillSample(type: 'wargod' | 'ceo' | 'suspense') {
  if (type === 'wargod') {
    form.rawText = `苏氏集团顶层会议室，气氛剑拔弩张。
苏明宇一把将财务报表摔在苏清雪面前，冷笑道：“苏清雪，三天内拿不出五千万填补亏损，就给我交出总裁位置，乖乖嫁给赵公子联姻！”
苏清雪眼眶通红，咬紧牙关：“这是你挪用公款陷害我，我绝不同意！”
就在全场亲戚出言嘲弄之时，会议室大门被猛然推开。
身着一袭素衣的林晨大步跨入，眼神如刀锋般锐利。
苏明宇见状勃然大怒，拍桌喝道：“林晨，你个倒插门吃软饭的窝囊废，这里是苏家高层会议，也是你配进来的？滚出去！”
林晨面色冷峻，负手而立，从怀中掷出一张通体漆黑、散发着金色龙纹的至尊黑卡：“五千万算什么？这张卡里有一百个亿，苏氏集团，从今天起由我全资接管！”
全场瞬间倒吸一口凉气，鸦雀无声。`
  } else if (type === 'ceo') {
    form.rawText = `暴雨倾盆的盛世华庭酒店门外，沈漫漫被淋得浑身湿透，怀中紧紧抱着母亲的病历单。
继母恶狠狠地夺过她的项链扔进雨水里：“一个小三生的野种，也配跟我们争遗产？滚得越远越好！”
一辆漆黑的劳斯莱斯幻影疾驰而至，溅起水花停在台阶前。
身着定制西装、气场尊贵的顾北冥撑着黑色大伞缓缓走下车，俯身将沈漫漫抱入怀中。
顾北冥眼神冷冽刺骨，扫向众人：“动我顾北冥的隐婚妻子，你们沈家是活腻了？”`
  } else {
    form.rawText = `午夜一点的迷雾码头，集装箱投下阴森的黑影。
刑警队长陆远握紧配枪，悄无声息地贴着锈迹斑驳的铁皮柜前进。
对讲机里传来搭档急促的喘息声：“陆队，内鬼已经把交易地点泄露了，有埋伏，快撤！”
一声刺耳的枪响撕破夜空，陆远猛然侧身翻滚，子弹擦着耳边击穿集装箱。
浓雾深处，一个戴着黑色面具的神秘人缓缓走出：“陆队长，查了三年，你终于找到我了。”`
  }
}

function applyDecomposeResult(res: ScriptDecomposeResult) {
  if (!res) return
  result.value = res
  skillEvents.value = res.skillEvents || skillEvents.value
  showThinkingDetails.value = false // 生成完毕后默认折叠至顶部精简条，用户可随时点击展开
  if (boundDrama.value?.title) {
    result.value.dramaTitle = boundDrama.value.title
  }
  if (boundDrama.value?.genre) {
    result.value.genre = boundDrama.value.genre
  }
  if (boundDrama.value?.aspectRatio) {
    form.aspectRatio = boundDrama.value.aspectRatio
  }
  if (boundDrama.value?.stylePreset) {
    form.stylePreset = boundDrama.value.stylePreset
  }
  selectedEpisodeIndex.value = 0
  activeTab.value = 'storyboard'
  ElMessage.success('剧本智能拆解成功！已自动提取角色、场景与分镜对白')
}

function buildDecomposeRequest(): ScriptDecomposeRequest {
  return {
    providerId: form.providerId,
    modelCode: form.modelCode,
    rawText: form.rawText,
    dramaId: form.dramaId,
    episodeId: form.episodeId,
    chapterTitle: form.chapterTitle,
    startEpisodeNo: form.startEpisodeNo,
    targetEpisodes: form.targetEpisodes,
    targetDurationPerEpisode: form.targetDurationPerEpisode || 300,
    aspectRatio: form.aspectRatio || '9:16',
    stylePreset: form.stylePreset || 'cinematic-realism',
    styleTone: form.styleTone,
    pacingPreset: form.pacingPreset || 'STANDARD',
    skillPolicy: {
      planner: { allowDynamicLoad: plannerDynamicSkills.value, requiredSkillNames: [...plannerRequiredSkills.value] },
      worker: { allowDynamicLoad: workerDynamicSkills.value, requiredSkillNames: [...workerRequiredSkills.value] }
    }
  }
}

async function handleStartInBackground() {
  if (!canDecompose.value || startingInBackground.value) return
  startingInBackground.value = true
  try {
    const taskId = await scriptApi.startDecomposeTask(buildDecomposeRequest())
    const taskCenter = useTaskCenterStore()
    taskCenter.start()
    void taskCenter.refreshActive()
    visible.value = false
    emit('panel-close')
    ElMessage.success(`后台拆解已启动（任务 #${String(taskId)}），可在任务中心重新打开`)
  } catch (error: any) {
    ElMessage.error(error?.message || '后台拆解启动失败')
  } finally {
    startingInBackground.value = false
  }
}

async function handleStartDecompose(existingTaskId?: string) {
  if (!existingTaskId && !canDecompose.value) return
  result.value = null
  discoveredAssets.value = null
  isStreaming.value = true
  showThinkingDetails.value = true
  streamOutputText.value = ''
  skillEvents.value = []
  elapsedSeconds.value = 0
  liveSegmentsList.value = []
  activeChannelTab.value = 'ALL'
  channelsMap.value = {
    ALL: { id: 'ALL', name: '📌 全部时序流水', text: '', status: 'RUNNING' },
    PLANNER: { id: 'PLANNER', name: '🎯 剧情分段', text: '', status: 'RUNNING' }
  }

  if (timer.value) clearInterval(timer.value)
  timer.value = setInterval(() => {
    elapsedSeconds.value++
  }, 1000)

  abortController.value = new AbortController()

  await scriptApi.decomposeStream(
    existingTaskId ? undefined : buildDecomposeRequest(),
    {
      signal: abortController.value.signal,
      onSegmentsInit(segments) {
        if (!segments || !Array.isArray(segments)) return
        liveSegmentsList.value = segments.map((seg: any) => ({
          id: String(seg.id).toUpperCase(),
          sequence: seg.sequence || 1,
          title: seg.title || seg.id,
          status: 'PENDING'
        }))
        segments.forEach((seg: any) => {
          const id = String(seg.id).toUpperCase()
          const title = seg.title || id
          channelsMap.value[id] = {
            id,
            name: `🎬 ${id} (${title})`,
            title,
            text: '',
            status: 'PENDING'
          }
        })
      },
      onAssetsDiscovered(assets) {
        if (!assets) return
        discoveredAssets.value = assets
      },
      onChannelChunk(channel, chunk) {
        if (!chunk) return

        // 1. 追加至全局时序流水
        if (!channelsMap.value.ALL) {
          channelsMap.value.ALL = { id: 'ALL', name: '📌 全部时序流水', text: '', status: 'RUNNING' }
        }
        channelsMap.value.ALL.text += chunk

        // 2. 专属通道直接追加 (0 正则，精准投递)
        const targetChannel = channel || 'ALL'
        if (targetChannel === 'PLANNER') {
          if (!channelsMap.value.PLANNER) {
            channelsMap.value.PLANNER = { id: 'PLANNER', name: '🎯 剧情分段', text: '', status: 'RUNNING' }
          }
          channelsMap.value.PLANNER.text += chunk
        } else if (targetChannel !== 'ALL') {
          if (!channelsMap.value[targetChannel]) {
            channelsMap.value[targetChannel] = {
              id: targetChannel,
              name: `🎬 ${targetChannel}`,
              text: '',
              status: 'RUNNING'
            }
          }
          channelsMap.value[targetChannel].status = 'RUNNING'
          channelsMap.value[targetChannel].text += chunk
          const liveSeg = liveSegmentsList.value.find(s => s.id === targetChannel)
          if (liveSeg && liveSeg.status === 'PENDING') {
            liveSeg.status = 'RUNNING'
          }
        }

        if (autoScroll.value) {
          nextTick(() => {
            if (streamConsoleRef.value) {
              streamConsoleRef.value.scrollTop = streamConsoleRef.value.scrollHeight
            }
          })
        }
      },
      onWorkerStatus(channel, status, shotsCount) {
        if (!channel) return
        if (channelsMap.value[channel]) {
          channelsMap.value[channel].status = status as any
          if (shotsCount != null) channelsMap.value[channel].shotsCount = shotsCount
        }
        const liveSeg = liveSegmentsList.value.find(s => s.id === channel)
        if (liveSeg) {
          liveSeg.status = status as any
          if (shotsCount != null) liveSeg.shotsCount = shotsCount
        }
      },
      onSkillEvent(event) {
        skillEvents.value.push(event)
        const line = `🧩 ${skillEventLabel(event)}\n`
        channelsMap.value.ALL.text += line
        streamOutputText.value += line
        const channel = event.stage === 'PLANNER' ? 'PLANNER' : event.segmentId
        if (channel) {
          if (!channelsMap.value[channel]) {
            channelsMap.value[channel] = { id: channel, name: channel === 'PLANNER' ? '🎯 剧情分段' : `🎬 ${channel}`, text: '', status: 'RUNNING' }
          }
          channelsMap.value[channel].text += line
        }
      },
      onChannelReset(channel) {
        if (channelsMap.value[channel]) channelsMap.value[channel].text = ''
      },
      onChunk(chunk) {
        if (!chunk) return
        streamOutputText.value += chunk
      },
      onResult(res) {
        if (res) {
          applyDecomposeResult(res)
        }
      },
      onError(err) {
        ElMessage.error(err.message || 'AI 拆解失败，请检查模型配置与网络连通性')
        isStreaming.value = false
        if (timer.value) {
          clearInterval(timer.value)
          timer.value = null
        }
      },
      onDone() {
        isStreaming.value = false
        if (timer.value) {
          clearInterval(timer.value)
          timer.value = null
        }
        // 如果 result 尚未被设置，尝试从流式累积输出中提取 JSON 进行恢复
        if (!result.value && streamOutputText.value) {
          try {
            const fallbackRes = tryExtractJson(streamOutputText.value)
            if (fallbackRes && (fallbackRes.episodes?.length || fallbackRes.characters?.length || fallbackRes.scenes?.length)) {
              applyDecomposeResult(fallbackRes as ScriptDecomposeResult)
            }
          } catch (e) {
            console.warn('从流式文本恢复分镜数据失败:', e)
          }
        }
      }
    },
    existingTaskId
  )
}

async function openTask(taskId: string) {
  let draft: ScriptDecomposeRequest | undefined
  try { draft = await scriptApi.getTaskDraft(taskId) }
  catch { /* Legacy task or expired draft. The preview may still be available. */ }
  await open(draft?.dramaId || undefined, draft?.episodeId)
  if (draft) {
    Object.assign(form, draft)
    plannerDynamicSkills.value = draft.skillPolicy?.planner?.allowDynamicLoad ?? false
    workerDynamicSkills.value = draft.skillPolicy?.worker?.allowDynamicLoad ?? false
    plannerRequiredSkills.value = [...(draft.skillPolicy?.planner?.requiredSkillNames || [])]
    workerRequiredSkills.value = [...(draft.skillPolicy?.worker?.requiredSkillNames || [])]
  }
  try {
    const preview = await scriptApi.getTaskPreview(taskId)
    if (preview) {
      applyDecomposeResult(preview)
      return
    }
  } catch {
    // Still running, failed, or only partial Redis events exist: replay the journal.
  }
  // 日志订阅会持续到任务结束；恢复路由无需等待后台任务完成。
  void handleStartDecompose(taskId)
}

function handleMoveToBackground() {
  visible.value = false
  ElMessage.info('任务继续后台运行，可在任务中心重新打开并查看日志')
}

async function handleCommit() {
  if (!result.value) return
  committing.value = true
  try {
    const isNewDrama = targetDramaType.value === 'NEW_DRAMA' || !form.dramaId
    const effectiveCommitMode = isNewDrama ? 'REPLACE_ALL' : commitMode.value

    const commitData: ScriptDecomposeCommit = {
      taskId: result.value.taskId || undefined,
      dramaId: isNewDrama ? undefined : form.dramaId,
      commitMode: effectiveCommitMode,
      targetEpisodeNo: isNewDrama ? 1 : (targetEpisodeNo.value || 1),
      dramaTitle: (!isNewDrama && boundDrama.value?.title) ? boundDrama.value.title : (customDramaTitle.value || result.value.dramaTitle || form.chapterTitle || 'AI一键生成短剧'),
      genre: (!isNewDrama && boundDrama.value?.genre) ? boundDrama.value.genre : (result.value.genre || 'DOMINANT_CEO'),
      synopsis: (!isNewDrama && boundDrama.value?.synopsis) ? boundDrama.value.synopsis : result.value.synopsis,
      aspectRatio: (!isNewDrama && boundDrama.value?.aspectRatio) ? boundDrama.value.aspectRatio : (form.aspectRatio || '9:16'),
      stylePreset: (!isNewDrama && boundDrama.value?.stylePreset) ? boundDrama.value.stylePreset : (form.stylePreset || 'cinematic-realism'),
      styleTone: (!isNewDrama && boundDrama.value?.styleTone) ? boundDrama.value.styleTone : (form.styleTone || result.value.styleTone),
      characters: result.value.characters || [],
      scenes: result.value.scenes || [],
      props: result.value.props || [],
      episodes: result.value.episodes || []
    }

    const newDramaId = await scriptApi.commit(commitData)
    if (newDramaId) {
      ElMessage.success('剧本与角色资产已成功一键入库！')
      visible.value = false
      emit('success', newDramaId)
      emit('panel-close')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '入库失败，请重试')
  } finally {
    committing.value = false
  }
}

defineExpose({
  open,
  openTask
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

:deep(.el-tabs__content) {
  flex: 1;
  overflow: hidden;
}

:deep(.custom-script-uploader .el-upload-dragger) {
  padding: 12px 8px;
  border-radius: 12px;
  background-color: #f8fafc;
  border: 1.5px dashed #cbd5e1;
  transition: all 0.2s ease;
}

:deep(.custom-script-uploader .el-upload-dragger:hover) {
  border-color: #3b82f6;
  background-color: #eff6ff;
}
</style>
