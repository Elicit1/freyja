<template>
  <el-dialog
    v-model="visible"
    :title="modalTitle"
    width="920px"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="!isStreaming"
    :show-close="!isStreaming"
    @close="handleCancel"
    class="shot-prompt-derive-dialog"
  >
    <div class="shot-derive-body space-y-3 max-h-[75vh] overflow-y-auto pr-1">
      <div v-if="restoringTask" class="flex items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
        <span class="inline-block h-2 w-2 rounded-full bg-slate-400 animate-pulse"></span>
        <span>正在读取任务状态，请稍候...</span>
      </div>
      <div v-else-if="isStreaming && !taskCenterStore.connected" class="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
        实时连接暂时中断，正在自动重连并补回缺失的提示词内容；后台任务仍在继续。
      </div>
      <div v-else-if="restoredTaskRunning" class="flex items-center gap-2 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-800">
        <span class="inline-block h-2 w-2 rounded-full bg-blue-500 animate-pulse"></span>
        <span>正在恢复提示词事件，进度和结果会继续显示在当前页面。</span>
      </div>
      <!-- 1. 当前镜头与剧情上下文横幅 (AI 推理基准) -->
      <div class="context-panel space-y-3">
        <div class="flex items-center justify-between">
          <span class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
            <span>🎬</span> 镜头与剧集背景上下文 (AI 推理基准)
          </span>
          <el-tag size="small" type="primary" effect="plain">
            {{ currentShotContext?.shotName || '当前镜头' }} · {{ currentShotContext?.shotTypeLocked ? currentShotContext?.shotType : '景别自适应' }} · {{ currentShotContext?.cameraMovementLocked ? currentShotContext?.cameraMovement : '运镜自适应' }}
          </el-tag>
        </div>

        <!-- 关联短剧自动带入状态提示 -->
        <div v-if="dramaInfo" class="flex items-center justify-between bg-indigo-50/80 border border-indigo-200/80 rounded-lg px-3 py-2 text-xs text-indigo-900">
          <div class="flex items-center gap-1.5 font-medium">
            <span>🎞️</span>
            <span>关联短剧：《{{ dramaInfo.title }}》</span>
            <span class="text-indigo-300">|</span>
            <span class="text-indigo-600 font-normal">已带入短剧全局风格与视觉基调指南</span>
          </div>
          <el-tag size="small" type="success" effect="plain" class="!font-medium">自动继承</el-tag>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-2 text-xs">
          <div class="context-item md:col-span-2">
            <span class="text-slate-400 block mb-0.5">本集剧情梗概 (Episode Summary)</span>
            <span class="font-medium text-slate-800 line-clamp-2">{{ episodeSummary || '暂无剧集简介（建议在剧集管理中完善）' }}</span>
          </div>
          <div v-if="currentShotContext?.scriptContent" class="context-item context-item--script md:col-span-2">
            <span class="text-indigo-600 font-bold block mb-0.5">📜 本镜头剧本文本 (Shot Script - 核心推导依据)</span>
            <span class="font-medium text-indigo-950">{{ currentShotContext.scriptContent }}</span>
          </div>
          <div class="context-item md:col-span-2">
            <span class="text-slate-400 block mb-0.5">当前镜头画面与动作描述 (Action Description)</span>
            <span class="font-medium text-slate-800">{{ currentShotContext?.actionDescription || '（未填写动作描述）' }}</span>
          </div>
        </div>

      </div>

      <!-- 一、资产选择区 (人物/造型、场景、关键道具) -->
        <ShotAssetReferenceComposer
          v-model:characterRefs="draft.characterRefs"
          v-model:resSceneId="draft.resSceneId"
          v-model:propRefs="draft.propRefs"
          :character-options="characterOptions"
          :scene-options="sceneOptions"
          :prop-options="propOptions"
          :disabled="isStreaming"
        />

      <!-- 二、参考图槽位 (Picture 1..N) 与 三、参考音频槽位 (Audio 1..N) -->
        <ReferenceMediaSlots
          v-model:ref-images="draft.refImages"
          v-model:ref-audios="draft.refAudios"
          :candidate-images="candidateImages"
          :candidate-audios="candidateAudios"
          :disabled="isStreaming"
        />

        <SkillSelector
          v-model="skillNames"
          :mode="executionMode"
          :disabled="isStreaming || isExportingPackage"
          @update:model-value="handleSkillChange"
        />

      <!-- 补充画风指导与大模型配置 -->
      <div class="settings-panel space-y-2.5">
          <!-- 生成方式与音频配置栏 -->
          <div class="settings-toolbar flex flex-wrap items-center justify-between gap-2.5">
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
            <!-- BGM 开关 (默认关闭) -->
            <div class="flex items-center gap-2 bg-slate-50 border border-slate-200/80 rounded px-2.5 py-1">
              <span class="text-[11px] font-semibold text-slate-700 flex items-center gap-1">
                🎵 背景配乐 (BGM)：
              </span>
              <el-tooltip
                content="关闭（默认推荐）：AI 提示词将强制禁用非现场配乐 (non_diegetic_music: N/A)，仅保留现场真实环境声与人物台词，避免配乐污染原片，便于后期统一混音配乐。"
                placement="top"
              >
                <span class="text-slate-400 hover:text-slate-600 cursor-pointer text-xs">ⓘ</span>
              </el-tooltip>
              <el-switch
                v-model="includeBgm"
                size="small"
                active-text="开启"
                inactive-text="禁用 (默认)"
                :disabled="isStreaming"
                @change="handleBgmChange"
              />
            </div>
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
              />
            </div>
            <!-- API 模式展示模型选择，MANUAL 模式展示提示 -->
            <div v-if="executionMode === 'API'">
              <label class="block text-[11px] font-semibold text-slate-600 mb-1">
                🤖 文本推理模型 <span class="text-rose-500">*</span>
              </label>
              <div class="flex gap-1.5">
                <el-select
                  v-model="providerId"
                  placeholder="选择提供商"
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
                  placeholder="选择模型"
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
                零 API 费用。系统直接生成完整任务 Prompt，支持 ChatGPT / Claude / Gemini。
              </div>
            </div>
          </div>

          <div>
            <label class="block text-[11px] font-semibold text-slate-700 mb-1">
              🎥 视觉风格基调 / 导演指南 (styleTone)
              <span class="text-[10px] text-slate-400 font-normal ml-1">光影、胶片质感、镜头暗调氛围</span>
            </label>
            <el-input
              v-model="styleTone"
              type="textarea"
              :rows="2"
              placeholder="如：冷调蓝灰光影、微弱轮廓逆光、35mm胶片颗粒质感、电影级大片氛围（自动带入短剧配置）"
              size="small"
              clearable
              :disabled="isStreaming"
            />
          </div>

          <div>
            <label class="block text-[11px] font-semibold text-slate-700 mb-1">
              💬 创作者补充特别指示 (可选)
            </label>
            <el-input
              v-model="instruction"
              placeholder="如：突出角色脸部压迫感、运镜缓慢推近、背景雨滴虚化等"
              size="small"
              clearable
              :disabled="isStreaming"
            />
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

        <!-- 外部 AI 附件顺序提示卡 (多模态素材提示) -->
        <div v-if="draft.refImages.length > 0 || draft.refAudios.length > 0" class="bg-amber-50/90 border border-amber-200 rounded-xl p-3 text-xs space-y-2">
          <div class="flex items-center justify-between">
            <div class="font-bold text-amber-900 flex items-center gap-1.5">
              <span>📎</span> 外部 AI 多模态附件上传指南 (重要)
            </div>
            <el-button size="small" link type="warning" @click="handleCopyManifestText">
              📋 复制附件清单文本
            </el-button>
          </div>
          <p class="text-[11px] text-amber-800 leading-relaxed">
            外部 AI 聊天窗口无法自动拉取本地素材。若外部 AI（如 ChatGPT / Claude / Gemini）支持上传附件，请<strong>严格按照下列编号顺序</strong>上传附件；若不上传，AI 将根据 Prompt 中的文字设定描述生成：
          </p>
          <div class="flex flex-wrap gap-1.5 pt-1">
            <div
              v-for="(img, idx) in draft.refImages"
              :key="'pic-' + idx"
              class="flex items-center gap-1 bg-white border border-amber-200 rounded px-2 py-1 text-[11px] text-amber-950 font-mono shadow-xs"
            >
              <span class="font-bold text-sky-700">&lt;Picture {{ idx + 1 }}&gt;</span>
              <span class="text-slate-800 truncate max-w-[120px]">{{ img.name }}</span>
              <span class="text-[9px] bg-amber-100 text-amber-800 px-1 rounded">{{ img.usageRole || 'SUBJECT' }}</span>
            </div>
            <div
              v-for="(aud, idx) in draft.refAudios"
              :key="'aud-' + idx"
              class="flex items-center gap-1 bg-white border border-amber-200 rounded px-2 py-1 text-[11px] text-amber-950 font-mono shadow-xs"
            >
              <span class="font-bold text-indigo-700">&lt;Audio {{ idx + 1 }}&gt;</span>
              <span class="text-slate-800 truncate max-w-[100px]">{{ aud.name || '音频' }}</span>
              <span class="text-[9px] bg-indigo-100 text-indigo-800 px-1 rounded">{{ aud.usageMode || 'VOICE_TIMBRE' }}</span>
            </div>
          </div>
        </div>

        <!-- 任务工作区页签 (Prompt 导出 / 结果粘贴) -->
        <el-tabs v-model="activeManualTab" type="border-card" class="rounded-xl overflow-hidden shadow-xs">
          <!-- 页签 1: 任务 Prompt -->
          <el-tab-pane label="📋 任务 Prompt" name="prompt">
            <div class="space-y-2.5">
              <div class="flex items-center justify-between text-xs text-slate-500 flex-wrap gap-2">
                <div class="flex items-center gap-2">
                  <span>模板版本: <el-tag size="small" type="info">{{ promptPackage?.templateVersion || '自动适配' }}</el-tag></span>
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
                    复制 System Prompt
                  </el-button>
                  <el-button
                    size="small"
                    plain
                    :disabled="!promptPackage?.userPrompt"
                    @click="handleCopy(promptPackage?.userPrompt, 'User Prompt')"
                  >
                    复制 User Prompt
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
                <el-alert
                  v-if="validationInfo.fingerprintMatched === false"
                  type="info"
                  show-icon
                  :closable="false"
                  title="注意：镜头上下文指纹与生成任务时不一致，镜头资产可能已发生变更，请核对预览内容。"
                />
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
      <el-alert
        v-if="executionMode === 'API' && backgroundStatus === 'failed' && backgroundError"
        type="error"
        :title="backgroundError"
        :closable="false"
        show-icon
      />
      <div v-if="executionMode === 'API' && (isStreaming || streamText)" class="rounded-xl border border-slate-800 bg-slate-900 text-slate-200 p-3.5 shadow-inner">
        <div class="flex items-center justify-between pb-2 mb-2 border-b border-slate-700/60 text-xs">
          <div class="flex items-center gap-2">
            <span class="inline-block w-2 h-2 rounded-full" :class="isStreaming ? 'bg-emerald-400 animate-pulse' : 'bg-blue-400'"></span>
            <span class="font-mono font-medium">
              {{ isStreaming ? '⚡ AI 实时构思与流式解析中...' : '✓ AI 提示词生成完毕' }}
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
        <!-- 素材变更失效警告横幅 (Staleness Guard) -->
        <el-alert
          v-if="isResultStale"
          type="error"
          show-icon
          :closable="false"
          title="⚠️ 素材配置或槽位顺序已发生变更！当前生成的提示词可能引用了错误的素材（如 <Picture N> 顺序错位）。建议点击右下方「🔄 重新 AI 分析」重新生成提示词！"
          class="!py-2"
        />

        <div class="flex items-center justify-between">
          <span class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
            <span>✨</span> {{ previewTitle }}
          </span>
          <el-tag :type="isResultStale ? 'danger' : 'success'" size="small" effect="dark">
            {{ isResultStale ? '⚠️ 素材已变更 (需要重新分析)' : '已生成完成' }}
          </el-tag>
        </div>

        <el-tabs v-model="activePreviewTab" type="border-card" class="rounded-xl overflow-hidden shadow-xs">
          <!-- 槽位 1: 🌅 首帧生图 Prompt (仅首尾帧模式展示) -->
          <el-tab-pane
            v-if="generationMode !== 'REFERENCE_MODE'"
            label="🌅 首帧生图 Prompt"
            name="firstFrame"
          >
            <div class="space-y-2 text-xs">
              <div class="text-[11px] text-slate-500">
                专供生图模型 (FLUX / SDXL / Midjourney) 渲染本镜头起始关键帧。融合角色样貌、首饰服装、道具与场景环境打光。
              </div>
              <div>
                <label class="font-semibold text-slate-700 block mb-1">首帧生图提示词 (First Frame Prompt)</label>
                <el-input
                  v-model="previewResult.firstFramePrompt"
                  type="textarea"
                  :rows="4"
                  class="font-mono text-xs"
                />
              </div>
            </div>
          </el-tab-pane>

          <!-- 槽位 2: 🏁 尾帧生图 Prompt (仅首尾帧模式展示) -->
          <el-tab-pane
            v-if="generationMode !== 'REFERENCE_MODE'"
            label="🏁 尾帧生图 Prompt"
            name="endFrame"
          >
            <div class="space-y-2 text-xs">
              <div class="text-[11px] text-slate-500">
                专供尾帧生图或首尾关键帧过渡。刻画镜头结束瞬间的主体动作形态、人物转向或场景状态演变。
              </div>
              <div>
                <label class="font-semibold text-purple-700 block mb-1">尾帧生图提示词 (End Frame Prompt)</label>
                <el-input
                  v-model="previewResult.endFramePrompt"
                  type="textarea"
                  :rows="4"
                  class="font-mono text-xs"
                />
              </div>
            </div>
          </el-tab-pane>

          <!-- 槽位 3: 🎥 视频运镜/提示词 Prompt -->
          <el-tab-pane
            :label="generationMode === 'REFERENCE_MODE' ? '🎬 视频提示词 (Prompt)' : '🎬 运镜动力 Prompt'"
            name="video"
          >
            <div class="space-y-2 text-xs">
              <div class="text-[11px] text-emerald-700 bg-emerald-50 p-2 rounded border border-emerald-100">
                {{ generationMode === 'REFERENCE_MODE' ? '专供多模态视频生成模型（结合参考图与音频）驱动动态画面演变与机位运动。' : '专供 MiniMax H3 FL2VA：首尾帧对齐声明与三个固定段落必须完整。' }}
              </div>
              <div>
                <label class="font-semibold text-slate-700 block mb-1">
                  {{ generationMode === 'REFERENCE_MODE' ? '视频提示词 (Prompt)' : 'H3 视频提示词 (Video Prompt)' }}
                </label>
                <el-input
                  v-if="generationMode === 'REFERENCE_MODE'"
                  v-model="previewResult.prompt"
                  type="textarea"
                  :rows="4"
                  class="font-mono text-xs"
                />
                <el-input
                  v-else
                  v-model="previewResult.videoPrompt"
                  type="textarea"
                  :rows="8"
                  class="font-mono text-xs"
                />
              </div>
            </div>
          </el-tab-pane>

          <!-- 槽位 4: 🚫 负向 Prompt -->
          <el-tab-pane v-if="generationMode === 'REFERENCE_MODE'" label="🚫 负向 Prompt" name="negative">
            <div class="space-y-2 text-xs">
              <div class="text-[11px] text-slate-500">
                画面质量防护词，剔除变形肢体、模糊失真、杂质文字等不良特征。
              </div>
              <div>
                <label class="font-semibold text-slate-700 block mb-1">最终负向提示词 (Negative Prompt)</label>
                <el-input
                  v-model="previewResult.negativePrompt"
                  type="textarea"
                  :rows="3"
                  class="font-mono text-xs"
                />
              </div>
            </div>
          </el-tab-pane>

          <!-- 槽位 5: 🎬 结构化导演决策计划 (DirectorPlan) -->
          <el-tab-pane
            v-if="previewResult.directorPlan"
            label="🎬 导演决策计划 (DirectorPlan)"
            name="directorPlan"
          >
            <div class="space-y-3 text-xs">
              <div class="grid grid-cols-1 md:grid-cols-3 gap-2">
                <div class="bg-white p-2.5 rounded border border-slate-200">
                  <span class="font-semibold text-slate-500 block mb-1">主选景别 / 机位角度</span>
                  <div class="flex items-center gap-1.5">
                    <el-tag size="small" type="primary">{{ previewResult.directorPlan.shotSize || '未指定' }}</el-tag>
                    <el-tag size="small" type="info">{{ previewResult.directorPlan.cameraAngle || '未指定' }}</el-tag>
                  </div>
                </div>
                <div class="bg-white p-2.5 rounded border border-slate-200 md:col-span-2">
                  <span class="font-semibold text-slate-500 block mb-1">叙事意图与戏剧目标</span>
                  <span class="text-slate-800 font-medium">{{ previewResult.directorPlan.narrativeIntent || '-' }}</span>
                </div>
              </div>

              <div v-if="previewResult.directorPlan.subjectAction || previewResult.directorPlan.gaze" class="grid grid-cols-1 md:grid-cols-2 gap-2">
                <div v-if="previewResult.directorPlan.subjectAction" class="bg-white p-2.5 rounded border border-slate-200">
                  <span class="font-semibold text-slate-500 block mb-1">主体动作演进</span>
                  <span class="text-slate-800">{{ previewResult.directorPlan.subjectAction }}</span>
                </div>
                <div v-if="previewResult.directorPlan.gaze" class="bg-white p-2.5 rounded border border-slate-200">
                  <span class="font-semibold text-slate-500 block mb-1">角色视线与焦点动向</span>
                  <span class="text-slate-800">{{ previewResult.directorPlan.gaze }}</span>
                </div>
              </div>

              <!-- Camera Beats 时间轴列表 -->
              <div v-if="previewResult.directorPlan.cameraBeats && previewResult.directorPlan.cameraBeats.length > 0" class="bg-white p-2.5 rounded border border-slate-200 space-y-2">
                <div class="flex items-center justify-between">
                  <span class="font-semibold text-slate-700 flex items-center gap-1.5">
                    <span>⏱️</span> 摄影机动作时间轴 (Camera Beats)
                  </span>
                  <span class="text-[10px] text-slate-400 font-mono">共 {{ previewResult.directorPlan.cameraBeats.length }} 个运镜节拍</span>
                </div>
                <div class="space-y-1.5">
                  <div
                    v-for="(beat, bIdx) in previewResult.directorPlan.cameraBeats"
                    :key="bIdx"
                    class="flex items-start gap-2 bg-slate-50 border border-slate-200 rounded p-2 text-xs"
                  >
                    <el-tag size="small" type="warning" effect="plain" class="font-mono font-bold shrink-0">
                      {{ beat.startSec }}s - {{ beat.endSec }}s
                    </el-tag>
                    <div class="flex-1 min-w-0 space-y-1">
                      <div class="flex items-center gap-2 flex-wrap">
                        <span class="font-bold text-slate-800">{{ beat.movement }}</span>
                        <el-tag v-if="beat.direction" size="small" type="info" class="!text-[10px] !h-4">{{ beat.direction }}</el-tag>
                        <el-tag v-if="beat.speed" size="small" type="success" class="!text-[10px] !h-4">{{ beat.speed }}</el-tag>
                        <span v-if="beat.narrativePurpose" class="text-slate-500 text-[11px]">— {{ beat.narrativePurpose }}</span>
                      </div>
                      <div v-if="beat.startCue || beat.stopCue" class="text-[11px] text-slate-600 flex gap-3">
                        <span v-if="beat.startCue">🎬 起始节点: {{ beat.startCue }}</span>
                        <span v-if="beat.stopCue">🛑 收束节点: {{ beat.stopCue }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 生效技能 -->
              <div v-if="previewResult.directorPlan.skills && previewResult.directorPlan.skills.length > 0" class="flex items-center gap-2 text-[11px] text-slate-500">
                <span>📚 调用的 AI Skills:</span>
                <el-tag
                  v-for="s in previewResult.directorPlan.skills"
                  :key="s.name"
                  size="small"
                  type="success"
                  effect="plain"
                >
                  {{ s.name }} v{{ s.version }}
                </el-tag>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-between">
        <div class="text-xs text-slate-400">
          <span v-if="isStreaming" class="text-emerald-600 flex items-center gap-1 font-mono">
            <span class="inline-block w-2 h-2 rounded-full bg-emerald-500 animate-ping"></span>
            流式生成中，请稍候...
          </span>
          <span v-else-if="restoringTask" class="text-slate-500 flex items-center gap-1">
            <span class="inline-block w-2 h-2 rounded-full bg-slate-400 animate-pulse"></span>
            正在读取任务状态...
          </span>
          <span v-else-if="restoredTaskRunning" class="text-blue-600 flex items-center gap-1 font-medium">
            <span class="inline-block w-2 h-2 rounded-full bg-blue-500 animate-pulse"></span>
            后台任务运行中，等待分析结果...
          </span>
          <span v-else-if="previewResult && isResultStale" class="text-rose-600 font-medium">
            ⚠️ 素材已发生变化，请重新分析或确认采纳
          </span>
          <span v-else-if="previewResult" class="text-slate-500">
            满意请点击右侧「确认采纳并回填」原子更新分镜全部配置
          </span>
        </div>

        <div class="flex gap-2">
          <el-button :disabled="isStreaming || restoringTask || restoredTaskRunning" @click="handleCancel">取消</el-button>

          <el-button
            v-if="isStreaming"
            type="primary"
            plain
            @click="handleMinimizeToBackground"
          >
            ↘ 收起并后台运行
          </el-button>

          <!-- API 模式按钮 -->
          <template v-if="executionMode === 'API'">
            <el-button
              v-if="!isStreaming && !restoringTask && !restoredTaskRunning"
              plain
              type="primary"
              @click="handleStartStream(true)"
            >
              ▣ 后台生成
            </el-button>
            <el-button
              v-if="!isStreaming && !restoringTask && !restoredTaskRunning && !previewResult"
              type="primary"
              @click="handleStartStream(false)"
            >
              ⚡ 开始 AI 分析
            </el-button>
            <el-button
              v-if="previewResult && !isStreaming && !restoringTask && !restoredTaskRunning"
              :type="isResultStale ? 'danger' : 'warning'"
              :plain="!isResultStale"
              @click="handleStartStream(false)"
            >
              🔄 重新 AI 分析
            </el-button>
            <el-button v-if="restoringTask" type="primary" plain disabled>
              正在读取任务状态
            </el-button>
            <el-button v-else-if="restoredTaskRunning" type="primary" plain disabled>
              后台任务运行中
            </el-button>
          </template>

          <!-- MANUAL 模式按钮 -->
          <template v-else>
            <el-button
              v-if="!previewResult"
              type="primary"
              :loading="isExportingPackage"
              @click="handleManualActionClick"
            >
              📋 生成并复制完整 Prompt
            </el-button>
            <el-button
              v-if="previewResult"
              plain
              type="warning"
              @click="activeManualTab = 'paste'"
            >
              🔄 重新粘贴/解析
            </el-button>
          </template>

          <el-button
            v-if="previewResult"
            type="success"
            :disabled="isStreaming || restoringTask || restoredTaskRunning"
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
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { shotApi, dramaApi } from '@/api/drama'
import { characterApi } from '@/api/res-character'
import { sceneApi } from '@/api/res-scene'
import { propApi } from '@/api/res-prop'
import { aiProviderApi } from '@/api/ai-provider'
import DictSelect from '@/components/DictSelect.vue'
import SkillSelector from '@/components/SkillSelector.vue'
import ShotAssetReferenceComposer from './ShotAssetReferenceComposer.vue'
import ReferenceMediaSlots from './ReferenceMediaSlots.vue'
import {
  useShotReferenceAssets,
  type ShotPromptDraft
} from '../composables/useShotReferenceAssets'
import { usePromptTaskStore } from '@/store/promptTask'
import { useTaskCenterStore, type PromptEvent } from '@/store/taskCenter'
import type {
  DramaShot,
  Drama,
  CharacterShotRefInfo,
  PropShotRefInfo,
  ShotRefImage,
  ShotRefAudio,
  ShotPromptDeriveDTO,
  ShotPromptDeriveVO,
  ShotPromptPackageVO,
  ShotPromptValidationResult,
  DirectorPlan
} from '@/types/drama'
import type { AiProviderVO, AiModel } from '@/types/ai-provider'
import type { ResCharacterOption, ResSceneOption, ResPropOption } from '@/types/resource'

const emit = defineEmits<{
  (e: 'apply', result: {
    prompt?: string
    firstFramePrompt?: string
    endFramePrompt?: string
    videoPrompt?: string
    negativePrompt?: string
    propRefs: PropShotRefInfo[]
    characterRefs: CharacterShotRefInfo[]
    resSceneId?: string | number
    refImages: ShotRefImage[]
    refAudios: ShotRefAudio[]
    directorPlan?: DirectorPlan
    directorPlanJson?: string
  }): void
  (e: 'task-attached', taskId: string): void
  (e: 'task-starting'): void
  (e: 'task-finished', taskId?: string): void
  (e: 'task-restoration-finished'): void
  (e: 'panel-close'): void
}>()

const visible = ref(false)
const isStreaming = ref(false)
const backgroundMode = ref(false)
const backgroundStatus = ref<'running' | 'completed' | 'failed'>('running')
const backgroundError = ref('')
const restoringTask = ref(false)
const restoredTaskRunning = ref(false)
const streamText = ref('')
const elapsedSeconds = ref(0)
const streamConsoleRef = ref<HTMLPreElement>()
const activePreviewTab = ref('firstFrame')
const generationMode = ref<'FIRST_LAST_FRAME' | 'REFERENCE_MODE'>('FIRST_LAST_FRAME')

// 双通道执行模式: API 自动流式 / MANUAL 外部 AI 手工生成
type ExecutionMode = 'API' | 'MANUAL'
const executionMode = ref<ExecutionMode>('API')
const DEFAULT_H3_SKILL = 'h3-prompt-writing'
const activeManualTab = ref<'prompt' | 'paste'>('prompt')
const promptPackage = ref<ShotPromptPackageVO | null>(null)
const rawManualResponse = ref('')
const isExportingPackage = ref(false)
const isParsingResult = ref(false)
const validationInfo = ref<ShotPromptValidationResult | null>(null)
const includeBgm = ref(false)

// 资产选项池
const characterOptions = ref<ResCharacterOption[]>([])
const sceneOptions = ref<ResSceneOption[]>([])
const propOptions = ref<ResPropOption[]>([])

// 弹窗内部一次性草稿 (Draft)
const draft = ref<ShotPromptDraft>({
  characterRefs: [],
  propRefs: [],
  resSceneId: undefined,
  refImages: [],
  refAudios: []
})

// 组合式函数管理素材池、去重与 AI 结果失效检测
const {
  candidateImages,
  candidateAudios,
  isResultStale,
  recordAiGeneratedSnapshot,
  clearAiGeneratedSnapshot
} = useShotReferenceAssets(draft, {
  characterOptions,
  sceneOptions,
  propOptions
})

const modalTitle = computed(() => {
  return generationMode.value === 'REFERENCE_MODE'
    ? '⚡ AI 智能生成多模态参考图提示词'
    : '⚡ AI 智能生成首尾帧提示词'
})

const previewTitle = computed(() => {
  return generationMode.value === 'REFERENCE_MODE'
    ? '多模态提示词结构化预览 (确认无误后可一键采纳回填)'
    : '首尾帧提示词结构化预览 (确认无误后可一键采纳回填)'
})

const currentShotContext = ref<DramaShot | null>(null)
const dramaInfo = ref<Drama | null>(null)
const episodeSummary = ref('')

const stylePreset = ref('')
const styleTone = ref('')
const instruction = ref('')
const skillNames = ref<string[]>([DEFAULT_H3_SKILL])

const providers = ref<AiProviderVO[]>([])
const models = ref<AiModel[]>([])
const providerId = ref<string | number | undefined>()
const modelCode = ref<string | undefined>()

const previewResult = ref<ShotPromptDeriveVO | null>(null)
const promptTaskStore = usePromptTaskStore()
const taskCenterStore = useTaskCenterStore()
const currentPromptTaskId = ref<string>()
let unsubscribePromptEvents: (() => void) | null = null
let timerInterval: any = null
let promptViewVersion = 0
let panelCloseEmitted = false

async function loadProviders() {
  try {
    const res = await aiProviderApi.getListEnabled()
    providers.value = res || []
    if (providers.value.length > 0 && !providerId.value) {
      providerId.value = String(providers.value[0].id)
      await handleProviderChange(providerId.value)
    }
  } catch (e) {
    console.warn('加载 AI 提供商失败', e)
  }
}

async function handleProviderChange(pid?: string | number) {
  modelCode.value = undefined
  models.value = []
  if (!pid) return
  try {
    const res = await aiProviderApi.getModelList(pid, 'CHAT')
    models.value = res?.filter(m => m.status === 1) || []
    if (models.value.length > 0) {
      modelCode.value = models.value[0].modelCode
    }
  } catch (e) {
    console.warn('加载模型列表失败', e)
  }
}

async function loadAssetOptions(dramaId: string | number | undefined, viewVersion: number) {
  if (!dramaId) return
  try {
    const [cRes, sRes, pRes] = await Promise.all([
      characterApi.getOptions(dramaId),
      sceneApi.getOptions(dramaId),
      propApi.getOptions(dramaId)
    ])
    if (viewVersion !== promptViewVersion) return
    if (cRes) characterOptions.value = cRes
    if (sRes) sceneOptions.value = sRes
    if (pRes) propOptions.value = pRes
  } catch (e) {
    console.warn('加载资产选项库失败', e)
  }
}

async function open(params: {
  shot: DramaShot
  characterRefs?: CharacterShotRefInfo[]
  propRefs?: PropShotRefInfo[]
  resSceneId?: string | number
  episodeSummary?: string
  dramaTitle?: string
  stylePreset?: string
  styleTone?: string
  generationMode?: 'FIRST_LAST_FRAME' | 'REFERENCE_MODE'
  refImages?: ShotRefImage[]
  refAudios?: ShotRefAudio[]
  characterOptions?: ResCharacterOption[]
  sceneOptions?: ResSceneOption[]
  propOptions?: ResPropOption[]
  taskId?: string
}) {
  const targetShotId = String(params.shot.id ?? '')
  const activeShotId = String(currentShotContext.value?.id ?? '')
  const sameActiveTask = !params.taskId
    || (currentPromptTaskId.value && String(params.taskId) === currentPromptTaskId.value)
  if (isStreaming.value && targetShotId === activeShotId && sameActiveTask) {
    backgroundMode.value = false
    visible.value = true
    ElMessage.info('该分镜的 AI 提示词任务正在运行，已为你展开对应任务面板')
    return
  }

  if (isStreaming.value) {
    // 旧任务继续由任务中心跟踪；当前面板切换到新的分镜会话。
    unsubscribePromptEvents?.()
    unsubscribePromptEvents = null
    cleanupTimer()
    isStreaming.value = false
    restoredTaskRunning.value = false
    backgroundMode.value = true
    backgroundStatus.value = 'running'
  }
  const viewVersion = ++promptViewVersion
  panelCloseEmitted = false

  unsubscribePromptEvents?.()
  unsubscribePromptEvents = null
  currentPromptTaskId.value = undefined
  backgroundMode.value = false
  restoringTask.value = Boolean(params.shot.id && params.taskId)
  restoredTaskRunning.value = false
  currentShotContext.value = { ...params.shot }
  dramaInfo.value = null

  // 1. 初始化草稿：深拷贝所有分镜数据，完全隔离外部表单
  draft.value = {
    characterRefs: (params.characterRefs || params.shot.characterRefs || []).map(c => ({ ...c })),
    propRefs: (params.propRefs || params.shot.propRefs || []).map(p => ({ ...p })),
    resSceneId: params.resSceneId !== undefined ? params.resSceneId : params.shot.resSceneId,
    refImages: (params.refImages || params.shot.refImages || []).map(img => ({ ...img })),
    refAudios: (params.refAudios || params.shot.refAudios || []).map(aud => ({ ...aud }))
  }

  // 2. 载入资产选项
  characterOptions.value = params.characterOptions ? [...params.characterOptions] : []
  sceneOptions.value = params.sceneOptions ? [...params.sceneOptions] : []
  propOptions.value = params.propOptions ? [...params.propOptions] : []

  if (params.shot.dramaId) {
    void loadAssetOptions(params.shot.dramaId, viewVersion)
  }

  generationMode.value = params.generationMode || params.shot.generationMode || 'FIRST_LAST_FRAME'
  episodeSummary.value = params.episodeSummary || ''
  executionMode.value = 'API'
  skillNames.value = [DEFAULT_H3_SKILL]
  promptPackage.value = null
  rawManualResponse.value = ''
  validationInfo.value = null
  activeManualTab.value = 'prompt'
  stylePreset.value = params.stylePreset || params.shot.stylePreset || ''
  styleTone.value = params.styleTone || ''
  instruction.value = ''
  includeBgm.value = false
  streamText.value = ''
  previewResult.value = null
  clearAiGeneratedSnapshot()
  activePreviewTab.value = generationMode.value === 'REFERENCE_MODE' ? 'video' : 'firstFrame'

  visible.value = true

  // 只在任务中心明确指定任务时恢复，普通打开弹窗始终作为一个全新的隔离任务面板。
  if (params.shot.id && params.taskId) {
    void restorePromptTask(targetShotId, params.taskId, viewVersion)
  } else {
    restoringTask.value = false
  }

  // 尝试拉取短剧基本信息补全画风基调
  if (params.shot.dramaId) {
    try {
      const d = await dramaApi.getById(params.shot.dramaId)
      if (viewVersion !== promptViewVersion) return
      if (d) {
        dramaInfo.value = d
        if (!stylePreset.value && d.stylePreset) stylePreset.value = d.stylePreset
        if (!styleTone.value && d.styleTone) styleTone.value = d.styleTone
      }
    } catch {}
  }

  loadProviders()
}

function handleCancel() {
  promptViewVersion++
  unsubscribePromptEvents?.()
  unsubscribePromptEvents = null
  isStreaming.value = false
  cleanupTimer()
  backgroundMode.value = false
  visible.value = false
  emitPanelClose()
}

function emitPanelClose() {
  if (panelCloseEmitted) return
  panelCloseEmitted = true
  emit('panel-close')
}

async function restorePromptTask(shotId: string, explicitTaskId: string | undefined, viewVersion: number) {
  try {
    const task = explicitTaskId
      ? await shotApi.getPromptTask(explicitTaskId)
      : await shotApi.getLatestPromptTask(shotId)
    if (viewVersion !== promptViewVersion) return
    if (!task) {
      // 只有 SSE 已经断开、且当前页面没有收到 task_created 时才会走到这里。
      // 没有可恢复任务时才显示失败，避免把普通“没有历史任务”误报成异常。
      if (restoredTaskRunning.value) {
        restoredTaskRunning.value = false
        backgroundStatus.value = 'failed'
        backgroundError.value = '实时连接已断开，未找到可恢复的后台任务'
      }
      return
    }

    const taskTargetId = String(task.targetId ?? '')
    if (taskTargetId && taskTargetId !== shotId) {
      backgroundStatus.value = 'failed'
      backgroundError.value = '该提示词任务属于其他分镜，已阻止载入当前分镜'
      restoredTaskRunning.value = false
      ElMessage.error(backgroundError.value)
      return
    }

    const inputRestoreStatus = restorePromptInputSnapshot(task.inputPayload, shotId)
    if (inputRestoreStatus === 'mismatch') {
      backgroundStatus.value = 'failed'
      backgroundError.value = '任务输入快照属于其他分镜，已阻止恢复'
      restoredTaskRunning.value = false
      ElMessage.error(backgroundError.value)
      return
    }
    if (inputRestoreStatus === 'unavailable') {
      ElMessage.warning('任务记录没有可用的输入快照，参考图片和音频暂按当前分镜内容显示')
    }

    const taskId = String(task.id)
    currentPromptTaskId.value = taskId
    attachPromptEvents(taskId)
    emit('task-attached', taskId)
    if (task.status === 'PENDING' || task.status === 'RUNNING' || task.status === 'RETRYING') {
      restoredTaskRunning.value = false
      isStreaming.value = true
      backgroundStatus.value = 'running'
      promptTaskStore.register(taskId, shotId)
      return
    }

    if ((task.status === 'SUCCESS' || task.status === 'PARTIAL_SUCCESS') && task.outputPayload) {
      const parsed = JSON.parse(task.outputPayload) as ShotPromptDeriveVO
      previewResult.value = parsed
      recordAiGeneratedSnapshot()
      backgroundStatus.value = 'completed'
      restoredTaskRunning.value = false
      emit('task-finished', taskId)
    } else if (task.status === 'FAILED' || task.status === 'CANCELLED') {
      backgroundStatus.value = 'failed'
      backgroundError.value = task.errorMessage || (task.status === 'CANCELLED' ? '提示词任务已取消' : '提示词分析失败')
      restoredTaskRunning.value = false
      emit('task-finished', taskId)
    } else {
      backgroundStatus.value = 'failed'
      backgroundError.value = task.errorMessage || `提示词任务已结束（${task.status}）`
      restoredTaskRunning.value = false
      isStreaming.value = false
      emit('task-finished', taskId)
    }
  } catch (e) {
    console.warn('恢复分镜提示词后台任务失败:', e)
    if (viewVersion === promptViewVersion && restoredTaskRunning.value) {
      restoredTaskRunning.value = false
      backgroundStatus.value = 'failed'
      backgroundError.value = '后台任务状态读取失败，请稍后从任务中心重试'
    }
  } finally {
    if (viewVersion === promptViewVersion) {
      restoringTask.value = false
      emit('task-restoration-finished')
    }
  }
}

function restorePromptInputSnapshot(inputPayload?: string, expectedShotId?: string): 'restored' | 'unavailable' | 'mismatch' {
  if (!inputPayload) return 'unavailable'
  try {
    const payload = JSON.parse(inputPayload) as ShotPromptDeriveDTO
    const payloadShotId = String(payload.shotId ?? '')
    if (expectedShotId && payloadShotId && payloadShotId !== expectedShotId) {
      return 'mismatch'
    }

    draft.value = {
      characterRefs: (payload.characterRefs || []).map(item => ({ ...item })),
      propRefs: (payload.propRefs || []).map(item => ({ ...item })),
      resSceneId: payload.resSceneId,
      refImages: (payload.refImages || []).map(item => ({ ...item })),
      refAudios: (payload.refAudios || []).map(item => ({ ...item }))
    }
    if (payload.generationMode) generationMode.value = payload.generationMode
    episodeSummary.value = payload.episodeSummary || ''
    stylePreset.value = payload.stylePreset || ''
    styleTone.value = payload.styleTone || ''
    instruction.value = payload.userInstruction || ''
    includeBgm.value = Boolean(payload.includeBgm)
    skillNames.value = [...(payload.requiredSkillNames || payload.selectedSkillNames || [DEFAULT_H3_SKILL])]
    providerId.value = payload.providerId
    modelCode.value = payload.modelCode

    if (currentShotContext.value) {
      currentShotContext.value = {
        ...currentShotContext.value,
        shotNo: payload.shotNo ?? currentShotContext.value.shotNo,
        shotName: payload.shotName ?? currentShotContext.value.shotName,
        shotType: payload.shotType ?? currentShotContext.value.shotType,
        cameraMovement: payload.cameraMovement ?? currentShotContext.value.cameraMovement,
        shotTypeLocked: payload.shotTypeLocked ?? currentShotContext.value.shotTypeLocked,
        cameraMovementLocked: payload.cameraMovementLocked ?? currentShotContext.value.cameraMovementLocked,
        duration: payload.duration ?? currentShotContext.value.duration,
        scriptContent: payload.scriptContent ?? currentShotContext.value.scriptContent,
        actionDescription: payload.actionDescription ?? currentShotContext.value.actionDescription,
        dialogue: payload.dialogue ?? currentShotContext.value.dialogue,
        dialogueSpeaker: payload.dialogueSpeaker ?? currentShotContext.value.dialogueSpeaker,
        voiceover: payload.voiceover ?? currentShotContext.value.voiceover,
        soundEffect: payload.soundEffect ?? currentShotContext.value.soundEffect,
        customScenePrompt: payload.customScenePrompt ?? currentShotContext.value.customScenePrompt
      }
    }
    return 'restored'
  } catch (error) {
    console.warn('恢复分镜提示词任务输入快照失败:', error)
    return 'unavailable'
  }
}

function handlePromptTaskCompleted(event: Event) {
  const detail = (event as CustomEvent<{ taskId?: string; result?: ShotPromptDeriveVO }>).detail
  if (!detail?.taskId || String(detail.taskId) !== String(currentPromptTaskId.value)) return
  restoredTaskRunning.value = false
  backgroundStatus.value = 'completed'
  emit('task-finished', currentPromptTaskId.value)
  cleanupTimer()
  isStreaming.value = false
  if (detail.result) {
    previewResult.value = detail.result
    recordAiGeneratedSnapshot()
  }
}

function handlePromptTaskFailed(event: Event) {
  const detail = (event as CustomEvent<{ taskId?: string; errorMessage?: string }>).detail
  if (!detail?.taskId || String(detail.taskId) !== String(currentPromptTaskId.value)) return
  restoredTaskRunning.value = false
  backgroundStatus.value = 'failed'
  emit('task-finished', currentPromptTaskId.value)
  cleanupTimer()
  isStreaming.value = false
  backgroundError.value = detail.errorMessage || '提示词分析失败'
}

function handleTaskCenterReconnected() {
  if (isStreaming.value && currentPromptTaskId.value) {
    void promptTaskStore.refreshTask(currentPromptTaskId.value)
  }
}

function handleMinimizeToBackground() {
  if (!isStreaming.value) return
  backgroundMode.value = true
  backgroundStatus.value = 'running'
  visible.value = false
  ElMessage.success('AI 已转入后台运行，可继续操作其他内容')
}

function cleanupTimer() {
  if (timerInterval) {
    clearInterval(timerInterval)
    timerInterval = null
  }
}

function getCharacterDesignDesc(characterId?: string | number, lookId?: string | number): string | undefined {
  if (!characterId) return undefined
  const char = characterOptions.value.find(c => String(c.id) === String(characterId))
  if (!char) return undefined
  if (lookId) {
    const look = char.outfits?.find(o => String(o.id) === String(lookId))
    if (look?.designDesc) return look.designDesc
  }
  const defaultLook = char.outfits?.find(o => o.isDefault === 1)
  return defaultLook?.designDesc
}

function getCharacterOutfitPrompt(characterId?: string | number, lookId?: string | number): string | undefined {
  if (!characterId) return undefined
  const char = characterOptions.value.find(c => String(c.id) === String(characterId))
  if (!char) return undefined
  if (lookId) {
    const look = char.outfits?.find(o => String(o.id) === String(lookId))
    if (look?.outfitPrompt) return look.outfitPrompt
  }
  const defaultLook = char.outfits?.find(o => o.isDefault === 1)
  return defaultLook?.outfitPrompt
}

function getCharacterAppearancePrompt(characterId?: string | number, lookId?: string | number): string | undefined {
  if (!characterId) return undefined
  const char = characterOptions.value.find(c => String(c.id) === String(characterId))
  if (!char) return undefined
  if (lookId) {
    const look = char.outfits?.find(o => String(o.id) === String(lookId))
    if (look?.appearancePrompt) return look.appearancePrompt
  }
  const defaultLook = char.outfits?.find(o => o.isDefault === 1)
  return defaultLook?.appearancePrompt
}

function buildDerivePayload(): ShotPromptDeriveDTO {
  const optionalText = (value?: string) => value?.trim() || undefined

  return {
    shotId: currentShotContext.value?.id,
    dramaId: currentShotContext.value?.dramaId,
    episodeId: currentShotContext.value?.episodeId,
    sceneId: currentShotContext.value?.sceneId,
    shotNo: currentShotContext.value?.shotNo,
    shotName: optionalText(currentShotContext.value?.shotName),
    shotType: currentShotContext.value?.shotTypeLocked ? optionalText(currentShotContext.value?.shotType) : undefined,
    cameraMovement: currentShotContext.value?.cameraMovementLocked ? optionalText(currentShotContext.value?.cameraMovement) : undefined,
    shotTypeLocked: Boolean(currentShotContext.value?.shotTypeLocked),
    cameraMovementLocked: Boolean(currentShotContext.value?.cameraMovementLocked),
    duration: currentShotContext.value?.duration,
    scriptContent: optionalText(currentShotContext.value?.scriptContent),
    actionDescription: optionalText(currentShotContext.value?.actionDescription),
    dialogue: optionalText(currentShotContext.value?.dialogue),
    dialogueSpeaker: optionalText(currentShotContext.value?.dialogueSpeaker),
    voiceover: optionalText(currentShotContext.value?.voiceover),
    soundEffect: optionalText(currentShotContext.value?.soundEffect),
    includeBgm: includeBgm.value,
    resSceneId: draft.value.resSceneId,
    customScenePrompt: optionalText(currentShotContext.value?.customScenePrompt),
    characterRefs: draft.value.characterRefs.map(c => ({
      ...c,
      designDesc: c.designDesc || getCharacterDesignDesc(c.characterId, c.lookId),
      outfitPrompt: c.outfitPrompt || getCharacterOutfitPrompt(c.characterId, c.lookId),
      appearancePrompt: c.appearancePrompt || getCharacterAppearancePrompt(c.characterId, c.lookId),
      actionPrompt: optionalText(c.actionPrompt),
      emotionPrompt: optionalText(c.emotionPrompt),
      positionTag: optionalText(c.positionTag)
    })),
    propRefs: draft.value.propRefs.length > 0 ? draft.value.propRefs : undefined,
    episodeSummary: optionalText(episodeSummary.value),
    stylePreset: optionalText(stylePreset.value),
    styleTone: optionalText(styleTone.value),
    providerId: providerId.value,
    modelCode: optionalText(modelCode.value),
    userInstruction: optionalText(instruction.value),
    generationMode: generationMode.value,
    refImages: draft.value.refImages.length > 0 ? draft.value.refImages : undefined,
    refAudios: draft.value.refAudios.length > 0 ? draft.value.refAudios : undefined,
    requiredSkillNames: executionMode.value === 'API' && skillNames.value.length > 0 ? skillNames.value : undefined,
    selectedSkillNames: executionMode.value === 'MANUAL' && skillNames.value.length > 0 ? skillNames.value : undefined
  }
}

function handleBgmChange() {
  if (executionMode.value === 'MANUAL' && promptPackage.value) {
    loadPromptPackage()
  }
}

function handleExecutionModeChange(mode: ExecutionMode) {
  executionMode.value = mode
  if (mode === 'MANUAL' && !promptPackage.value) {
    loadPromptPackage()
  }
}

function handleSkillChange() {
  if (executionMode.value === 'MANUAL' && visible.value) {
    loadPromptPackage()
  }
}

async function handleManualActionClick() {
  if (!promptPackage.value) {
    await loadPromptPackage()
  }
  if (promptPackage.value?.combinedPrompt) {
    await handleCopy(promptPackage.value.combinedPrompt, '完整任务 Prompt')
  }
}

async function loadPromptPackage() {
  if (isExportingPackage.value) return
  isExportingPackage.value = true
  try {
    const payload = buildDerivePayload()
    payload.providerId = undefined
    payload.modelCode = undefined
    const res = await shotApi.derivePromptPackage(payload)
    promptPackage.value = res
    ElMessage.success('已生成最新任务 Prompt 包！')
  } catch (err: any) {
    ElMessage.error(err.message || '生成 Prompt 任务包失败')
  } finally {
    isExportingPackage.value = false
  }
}

async function handleCopy(text?: string, label = '内容') {
  if (!text) {
    ElMessage.warning('暂无可复制的内容')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label}已复制到剪贴板！`)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    ElMessage.success(`${label}已复制到剪贴板！`)
  }
}

function handleCopyManifestText() {
  const lines: string[] = ['【多模态附件上传建议顺序】:']
  if (draft.value.refImages.length > 0) {
    draft.value.refImages.forEach((img, idx) => {
      lines.push(`<Picture ${idx + 1}>: ${img.name} (${img.usageRole || 'SUBJECT'})`)
    })
  }
  if (draft.value.refAudios.length > 0) {
    draft.value.refAudios.forEach((aud, idx) => {
      lines.push(`<Audio ${idx + 1}>: ${aud.name || '音频'} (${aud.usageMode || 'VOICE_TIMBRE'})`)
    })
  }
  handleCopy(lines.join('\n'), '参考素材清单')
}

async function handleParseManualResponse() {
  if (!rawManualResponse.value.trim()) {
    ElMessage.warning('请先粘贴外部 AI 返回的内容')
    return
  }
  isParsingResult.value = true
  validationInfo.value = null
  try {
    const res = await shotApi.parseDerivedPrompt({
      shotId: currentShotContext.value?.id,
      dramaId: currentShotContext.value?.dramaId,
      episodeId: currentShotContext.value?.episodeId,
      sceneId: currentShotContext.value?.sceneId,
      shotNo: currentShotContext.value?.shotNo,
      generationMode: generationMode.value,
      rawResponse: rawManualResponse.value,
      contextFingerprint: promptPackage.value?.contextFingerprint,
      dialogue: currentShotContext.value?.dialogue,
      characterRefs: draft.value.characterRefs,
      propRefs: draft.value.propRefs.length > 0 ? draft.value.propRefs : undefined,
      refImages: draft.value.refImages.length > 0 ? draft.value.refImages : undefined,
      refAudios: draft.value.refAudios.length > 0 ? draft.value.refAudios : undefined,
      includeBgm: includeBgm.value,
      selectedSkillNames: skillNames.value.length > 0 ? skillNames.value : undefined
    })

    validationInfo.value = res
    if (res.result) {
      previewResult.value = res.result
      recordAiGeneratedSnapshot()
      activePreviewTab.value = generationMode.value === 'REFERENCE_MODE' ? 'video' : 'firstFrame'
      if (!res.errors || res.errors.length === 0) {
        ElMessage.success('外部 AI 结果解析校验通过！已载入下方预览卡片。')
      } else {
        ElMessage.warning('结果已解析，但存在硬性校验错误，请根据上方红色提示核对修改。')
      }
    } else {
      ElMessage.error(res.errors?.[0] || '未能解析出有效提示词结构')
    }
  } catch (err: any) {
    ElMessage.error(err.message || '解析外部结果失败')
  } finally {
    isParsingResult.value = false
  }
}

function handleViewJsonExample() {
  const referencePrompt = "subject_definitions:\n<Subject 1> is the ...\n\nsummary:\n...\n\nretention_analysis:\n...\n\ndetailed_description:\n...\n\noverall_soundscape:\n...\n\nnon_diegetic_music:\n..."
  const duration = Number(currentShotContext.value?.duration ?? 5).toFixed(2)
  const firstLastPrompt = `How the reference pictures align with the target video — Picture 1 aligns with the 0.00-second mark of the target video; Picture 2 aligns with the ${duration}-second mark of the target video.\nintegrated_multimodal_description: The camera follows the subject continuously from the first frame to the final pose.\noverall_soundscape: Ambient room tone.\nnon_diegetic_music: N/A`
  const example = generationMode.value === 'REFERENCE_MODE'
    ? {
        prompt: referencePrompt,
        videoPrompt: referencePrompt,
        negativePrompt: "low quality, blurry, deformed limbs"
      }
    : {
        firstFramePrompt: "Cinematic medium close-up of ... in 35mm film style ...",
        endFramePrompt: "Ending moment frozen frame of ... with dramatic lighting ...",
        videoPrompt: firstLastPrompt
      }

  ElMessageBox.alert(
    `<pre class="bg-slate-900 text-emerald-400 p-3 rounded font-mono text-xs overflow-x-auto select-all max-h-96 leading-relaxed">${JSON.stringify(example, null, 2)}</pre>`,
    'JSON 输出格式示例',
    {
      dangerouslyUseHTMLString: true,
      customClass: '!max-w-xl'
    }
  )
}

function attachPromptEvents(taskId: string) {
  unsubscribePromptEvents?.()
  unsubscribePromptEvents = taskCenterStore.subscribePrompt(taskId, (event: PromptEvent) => {
    if (String(event.taskId) !== currentPromptTaskId.value) return
    if (event.type === 'stage' || event.type === 'chunk') {
      streamText.value += event.data
      nextTick(() => {
        if (streamConsoleRef.value) streamConsoleRef.value.scrollTop = streamConsoleRef.value.scrollHeight
      })
    } else if (event.type === 'result') {
      try {
        previewResult.value = JSON.parse(event.data) as ShotPromptDeriveVO
        recordAiGeneratedSnapshot()
      } catch (e) {
        backgroundError.value = '提示词结果解析失败'
        console.error(e)
      }
    } else if (event.type === 'error') {
      cleanupTimer()
      isStreaming.value = false
      restoredTaskRunning.value = false
      backgroundStatus.value = 'failed'
      backgroundError.value = event.data
      emit('task-finished', taskId)
      void promptTaskStore.refreshTask(taskId)
      unsubscribePromptEvents?.()
      unsubscribePromptEvents = null
      if (visible.value) ElMessage.error(event.data)
      else ElNotification.error({ title: 'AI 提示词生成失败', message: event.data })
    } else if (event.type === 'done') {
      const wasRunning = isStreaming.value || restoredTaskRunning.value
      cleanupTimer()
      isStreaming.value = false
      restoredTaskRunning.value = false
      backgroundStatus.value = 'completed'
      emit('task-finished', taskId)
      void promptTaskStore.refreshTask(taskId)
      unsubscribePromptEvents?.()
      unsubscribePromptEvents = null
      if (wasRunning && visible.value) ElMessage.success('AI 提示词已生成完成！')
      else if (wasRunning) ElNotification.success({ title: 'AI 提示词已生成完成', message: '请打开任务中心查看并采纳结果' })
    }
  })
}

async function handleStartStream(runInBackground = false) {
  if (isStreaming.value || restoringTask.value || restoredTaskRunning.value) return
  if (!providerId.value || !modelCode.value) {
    ElMessage.warning('请选择 AI 文本推理模型提供商与模型编码')
    return
  }
  isStreaming.value = true
  emit('task-starting')
  backgroundMode.value = runInBackground
  backgroundStatus.value = 'running'
  backgroundError.value = ''
  streamText.value = ''
  previewResult.value = null
  elapsedSeconds.value = 0

  cleanupTimer()
  timerInterval = setInterval(() => {
    elapsedSeconds.value++
  }, 1000)

  const payload = buildDerivePayload()
  const viewVersion = promptViewVersion
  if (runInBackground) {
    visible.value = false
    ElMessage.success('AI 提示词任务已在后台运行，可继续操作其他内容')
  }
  restoredTaskRunning.value = false
  unsubscribePromptEvents?.()
  unsubscribePromptEvents = null
  try {
    const taskId = String(await shotApi.startPromptTask(payload))
    promptTaskStore.register(taskId, payload.shotId !== undefined ? String(payload.shotId) : undefined)
    emit('task-attached', taskId)
    if (viewVersion !== promptViewVersion) {
      void promptTaskStore.refreshTask(taskId)
      return
    }
    currentPromptTaskId.value = taskId
    attachPromptEvents(taskId)
    void promptTaskStore.refreshTask(taskId)
  } catch (err: any) {
    if (viewVersion !== promptViewVersion) {
      emit('task-finished')
      ElNotification.error({ title: 'AI 提示词任务提交失败', message: err?.message || '创建提示词任务失败' })
      return
    }
    cleanupTimer()
    isStreaming.value = false
    emit('task-finished')
    backgroundStatus.value = 'failed'
    backgroundError.value = err?.message || '创建提示词任务失败'
    ElMessage.error(backgroundError.value)
  }
}

function doApply() {
  if (!previewResult.value) return
  emit('apply', {
    ...(generationMode.value === 'REFERENCE_MODE'
      ? { prompt: previewResult.value.prompt || previewResult.value.videoPrompt || '',
          videoPrompt: previewResult.value.videoPrompt || previewResult.value.prompt || '',
          firstFramePrompt: '', endFramePrompt: '',
          negativePrompt: previewResult.value.negativePrompt || '' }
      : { firstFramePrompt: previewResult.value.firstFramePrompt || '',
          endFramePrompt: previewResult.value.endFramePrompt || '',
          videoPrompt: previewResult.value.videoPrompt || '' }),
    directorPlan: previewResult.value.directorPlan,
    directorPlanJson: previewResult.value.directorPlan ? JSON.stringify(previewResult.value.directorPlan) : undefined,
    characterRefs: draft.value.characterRefs.map(item => ({ ...item })),
    propRefs: draft.value.propRefs.map(item => ({ ...item })),
    resSceneId: draft.value.resSceneId,
    refImages: draft.value.refImages.map(item => ({ ...item })),
    refAudios: draft.value.refAudios.map(item => ({ ...item }))
  })
  ElMessage.success('已采纳提示词及资产编排，原子回填至分镜！')
  backgroundMode.value = false
  visible.value = false
  emitPanelClose()
}

function handleConfirmApply() {
  if (!previewResult.value) return

  if (isResultStale.value) {
    ElMessageBox.confirm(
      '检测到素材或槽位顺序在 AI 分析完成后发生了变更。继续采纳可能导致提示词中引用的 <Picture N> 顺序与当前素材错位。确定仍要采纳并回填吗？',
      '素材变更提示',
      {
        type: 'warning',
        confirmButtonText: '确定仍要采纳',
        cancelButtonText: '返回重新分析'
      }
    ).then(() => {
      doApply()
    }).catch(() => {})
  } else {
    doApply()
  }
}

onBeforeUnmount(() => {
  unsubscribePromptEvents?.()
  cleanupTimer()
  window.removeEventListener('shot-prompt-task-completed', handlePromptTaskCompleted)
  window.removeEventListener('shot-prompt-task-failed', handlePromptTaskFailed)
  window.removeEventListener('task-center-connected', handleTaskCenterReconnected)
})

onMounted(() => {
  window.addEventListener('shot-prompt-task-completed', handlePromptTaskCompleted)
  window.addEventListener('shot-prompt-task-failed', handlePromptTaskFailed)
  window.addEventListener('task-center-connected', handleTaskCenterReconnected)
})

defineExpose({
  open
})
</script>

<style scoped>
.shot-prompt-derive-dialog :deep(.el-dialog__body) {
  padding-top: 10px;
  padding-bottom: 10px;
}

.shot-prompt-derive-dialog :deep(.el-dialog__header) {
  padding-bottom: 12px;
  border-bottom: 1px solid #e2e8f0;
}

.shot-prompt-derive-dialog :deep(.el-dialog__title) {
  font-size: 15px;
  font-weight: 700;
  color: #172033;
}

.shot-derive-body {
  scrollbar-width: thin;
  scrollbar-color: #cbd5e1 transparent;
}

.context-panel {
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  background: linear-gradient(135deg, #f8fafc 0%, #f8fbff 100%);
}

.context-item {
  padding: 7px 0;
  border-bottom: 1px solid #e2e8f0;
}

.context-item:last-child {
  border-bottom: 0;
}

.context-item--script {
  padding: 8px 10px;
  border: 1px solid #c7d2fe;
  border-radius: 9px;
  background: rgba(238, 242, 255, .72);
}

.settings-panel {
  padding: 12px 14px;
  border-top: 1px solid #e2e8f0;
  border-bottom: 1px solid #e2e8f0;
  background: #fbfcfe;
}

.settings-toolbar {
  padding-bottom: 10px;
  border-bottom: 1px solid #e2e8f0;
}

</style>
