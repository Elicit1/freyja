<template>
  <div class="h-full flex flex-col p-6 space-y-6 overflow-y-auto bg-[var(--app-bg)]">
    <!-- Header -->
    <div class="flex items-center justify-between pb-4 border-b border-[var(--border-default)]">
      <div class="flex items-center gap-3">
        <div class="w-10 h-10 rounded-xl bg-gradient-to-tr from-indigo-500 to-purple-600 flex items-center justify-center text-white shadow-md">
          <el-icon class="text-xl"><ZoomIn /></el-icon>
        </div>
        <div>
          <div class="flex items-center gap-2">
            <h1 class="text-lg font-bold text-[var(--text-primary)]">视频超分辨率增强 (Video Upscale)</h1>
            <span
              v-if="selectedModel"
              class="px-2 py-0.5 text-xs font-semibold rounded bg-indigo-500/10 text-indigo-500 border border-indigo-500/20"
            >
              {{ selectedModel.modelName || selectedModel.modelCode }} ({{ formData.scale }}×)
            </span>
          </div>
          <p class="text-xs text-[var(--text-secondary)] mt-0.5">
            基于深度学习重建视频细节，无损超分辨率放大，保留原片帧率与音频轨道
          </p>
        </div>
      </div>
      <div class="flex items-center gap-2">
        <el-button @click="loadHistoryTasks" :loading="loadingHistory" plain size="small">
          <el-icon class="mr-1"><Refresh /></el-icon> 刷新任务
        </el-button>
        <el-button type="primary" plain size="small" @click="historyDrawer = true">
          <el-icon class="mr-1"><List /></el-icon> 历史超分记录 ({{ totalTasks }})
        </el-button>
      </div>
    </div>

    <!-- Main Workspace Grid -->
    <div class="grid grid-cols-1 lg:grid-cols-12 gap-6 flex-1 items-start">
      <!-- Left Column: Source & Parameters (5 cols) -->
      <div class="lg:col-span-5 space-y-5">
        <!-- 1. Video Input Card -->
        <el-card shadow="never" class="!bg-[var(--surface)] !border-[var(--border-default)] !rounded-xl">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-semibold text-sm flex items-center gap-2">
                <el-icon class="text-indigo-400"><Film /></el-icon> 1. 选择源视频文件
              </span>
              <el-tag v-if="videoProbe" size="small" type="success" effect="plain">
                {{ videoProbe.width }}×{{ videoProbe.height }} @ {{ videoProbe.fps || 24 }}fps
              </el-tag>
            </div>
          </template>

          <div class="space-y-4">
            <!-- 来源模式切换 (分镜选择 / 本地上传 / URL高级) -->
            <el-segmented
              v-model="sourceInputMode"
              :options="sourceModeOptions"
              size="small"
              class="w-full"
              @change="handleSourceModeChange"
            />

            <!-- 模式 A: 从分镜选择 (默认推荐) -->
            <div v-if="sourceInputMode === 'SHOT'" class="space-y-3">
              <!-- 已选分镜卡片 -->
              <div
                v-if="selectedSource"
                class="p-3.5 rounded-xl bg-slate-50 dark:bg-slate-900/50 border border-indigo-200/80 dark:border-indigo-900/50 space-y-2.5"
              >
                <div class="flex items-start justify-between gap-2">
                  <div class="space-y-1 min-w-0 flex-1">
                    <div class="text-[11px] text-slate-500 dark:text-slate-400 truncate">
                      {{ selectedSource.dramaTitle ? `《${selectedSource.dramaTitle}》` : '短剧' }}
                      <span v-if="selectedSource.episodeTitle"> / {{ selectedSource.episodeTitle }}</span>
                      <span v-if="selectedSource.sceneName"> / {{ selectedSource.sceneName }}</span>
                    </div>
                    <div class="flex items-center gap-2">
                      <span class="font-mono font-bold text-xs px-1.5 py-0.5 rounded bg-indigo-100 dark:bg-indigo-950 text-indigo-700 dark:text-indigo-300">
                        S{{ selectedSource.shotNo }}
                      </span>
                      <span class="text-xs font-semibold text-slate-800 dark:text-slate-200 truncate">
                        {{ selectedSource.shotName || '分镜' }}
                      </span>
                    </div>
                  </div>

                  <div class="flex items-center gap-1">
                    <el-button size="small" link type="primary" @click="openShotPicker">
                      更换
                    </el-button>
                    <el-button size="small" link type="info" @click="clearSelectedSource">
                      清除
                    </el-button>
                  </div>
                </div>

                <!-- 来源版本与缩略预览 -->
                <div class="flex items-center justify-between text-xs pt-1 border-t border-slate-200/60 dark:border-slate-800">
                  <div class="flex items-center gap-2">
                    <span
                      v-if="selectedSource.sourceType === 'SHOT_CURRENT'"
                      class="text-emerald-600 bg-emerald-50 dark:bg-emerald-950/40 border border-emerald-200 dark:border-emerald-800 text-[10px] px-1.5 py-0.5 rounded flex items-center gap-1"
                    >
                      <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
                      当前生效视频
                    </span>
                    <span
                      v-else-if="selectedSource.sourceType === 'SHOT_VIDEO_TAKE'"
                      class="text-indigo-600 bg-indigo-50 dark:bg-indigo-950/40 border border-indigo-200 dark:border-indigo-800 text-[10px] px-1.5 py-0.5 rounded flex items-center gap-1"
                    >
                      <span>Take #{{ selectedSource.takeNo }}</span>
                    </span>
                  </div>

                  <div class="text-[11px] text-slate-400 font-mono truncate max-w-[200px]" :title="selectedSource.sourceVideoUrl">
                    {{ selectedSource.sourceVideoUrl.split('/').pop() }}
                  </div>
                </div>

                <!-- 🌟 分镜成片回填设置 (选择视频时直接决定) -->
                <div class="pt-2.5 border-t border-slate-200/80 dark:border-slate-800 space-y-1.5 bg-indigo-50/50 dark:bg-indigo-950/20 -mx-3.5 -mb-3.5 p-3 rounded-b-xl">
                  <div class="text-[11px] font-semibold text-indigo-700 dark:text-indigo-300 flex items-center justify-between">
                    <span class="flex items-center gap-1.5">🎬 成片回填策略 (选择视频时预设)</span>
                    <el-tag size="small" type="primary" effect="plain" class="!text-[10px]">自动入库</el-tag>
                  </div>
                  <div class="space-y-1">
                    <el-checkbox v-model="formData.autoSaveToShot" size="small" class="!h-auto !mr-0">
                      <span class="text-xs text-[var(--text-primary)]">处理成功后自动保存为分镜历史 Take (推荐)</span>
                    </el-checkbox>
                    <div v-if="formData.autoSaveToShot" class="pl-5">
                      <el-checkbox v-model="formData.setAsCurrent" size="small" class="!h-auto !mr-0">
                        <span class="text-xs text-[var(--text-secondary)]">自动设为分镜当前视频 (默认选择)</span>
                      </el-checkbox>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 未选择分镜时引导卡片 -->
              <div
                v-else
                class="py-8 px-4 rounded-xl border-2 border-dashed border-[var(--border-default)] hover:border-indigo-400 transition-colors flex flex-col items-center justify-center space-y-2.5 cursor-pointer bg-[var(--surface-muted)]"
                @click="openShotPicker"
              >
                <div class="w-12 h-12 rounded-full bg-indigo-500/10 flex items-center justify-center text-indigo-500 text-2xl">
                  <Film />
                </div>
                <div class="text-xs font-semibold text-[var(--text-primary)]">
                  点击从短剧分镜库选择视频
                </div>
                <div class="text-[11px] text-[var(--text-secondary)] text-center">
                  支持按短剧、剧集、场次筛选，可直接选择当前视频或历史 Take 候选版本
                </div>
                <el-button size="small" type="primary" plain class="mt-1">
                  <el-icon class="mr-1"><FolderOpened /></el-icon> 浏览分镜库
                </el-button>
              </div>
            </div>

            <!-- 模式 B: 本地视频上传 -->
            <div v-else-if="sourceInputMode === 'UPLOAD'" class="space-y-3">
              <el-upload
                class="video-uploader"
                drag
                action="#"
                :show-file-list="false"
                :http-request="handleVideoUpload"
                accept="video/mp4,video/quicktime,video/webm"
              >
                <div v-if="uploading" class="py-8 flex flex-col items-center justify-center space-y-2">
                  <el-icon class="text-4xl text-indigo-400 is-loading"><Loading /></el-icon>
                  <div class="text-xs text-[var(--text-secondary)]">正在上传视频至 MinIO 存储...</div>
                </div>
                <div v-else-if="formData.sourceVideoUrl" class="py-4 flex flex-col items-center justify-center space-y-2">
                  <el-icon class="text-3xl text-emerald-400"><CircleCheckFilled /></el-icon>
                  <div class="text-xs font-medium text-emerald-400">本地视频已上传就绪</div>
                  <div class="text-[11px] text-[var(--text-secondary)] truncate max-w-[280px]">
                    {{ formData.sourceVideoUrl }}
                  </div>
                  <el-button size="small" link type="primary">点击更换视频</el-button>
                </div>
                <div v-else class="py-6 flex flex-col items-center justify-center space-y-2">
                  <el-icon class="text-4xl text-slate-400"><UploadFilled /></el-icon>
                  <div class="text-xs text-[var(--text-primary)] font-medium">点击或拖拽本地视频文件上传</div>
                  <div class="text-[11px] text-[var(--text-secondary)]">支持 MP4, MOV, WebM 格式</div>
                </div>
              </el-upload>
            </div>

            <!-- 模式 C: 外部 / MinIO URL 直连 -->
            <div v-else class="space-y-2">
              <div class="text-xs text-[var(--text-secondary)]">直接输入 MinIO / 外部安全视频 URL：</div>
              <el-input
                v-model="formData.sourceVideoUrl"
                placeholder="http://localhost:9000/video-assets/..."
                clearable
                size="small"
                @change="handleUrlChange"
              >
                <template #append>
                  <el-button @click="triggerProbe" :loading="probing">探测信息</el-button>
                </template>
              </el-input>
            </div>

            <!-- 视频探测元数据看板 -->
            <div v-if="videoProbe" class="p-2.5 rounded-lg bg-[var(--surface-muted)] border border-[var(--border-default)] text-xs grid grid-cols-2 gap-2 text-[var(--text-secondary)]">
              <div>📐 分辨率：<span class="text-[var(--text-primary)] font-mono">{{ videoProbe.width }}×{{ videoProbe.height }}</span></div>
              <div>🎞️ 帧率：<span class="text-[var(--text-primary)] font-mono">{{ videoProbe.fps || 24 }} FPS</span></div>
              <div>⏱️ 时长：<span class="text-[var(--text-primary)] font-mono">{{ videoProbe.duration ? videoProbe.duration + 's' : '未知' }}</span></div>
              <div>🔊 音频：<span class="text-[var(--text-primary)]">{{ videoProbe.hasAudio ? '包含 (' + (videoProbe.audioCodec || 'AAC') + ')' : '无音频' }}</span></div>
            </div>
          </div>
        </el-card>

        <!-- 2. Processing Parameters Card -->
        <el-card shadow="never" class="!bg-[var(--surface)] !border-[var(--border-default)] !rounded-xl">
          <template #header>
            <span class="font-semibold text-sm flex items-center gap-2">
              <el-icon class="text-indigo-400"><Setting /></el-icon> 2. 超分辨率模型与参数配置
            </span>
          </template>

          <el-form :model="formData" label-position="top" size="small" class="space-y-3">
            <!-- Provider Selection -->
            <el-form-item label="AI 提供商 (FastAPI / 视频处理网关)">
              <el-select
                v-model="formData.providerId"
                placeholder="请选择已启用的提供商"
                class="w-full"
                @change="handleProviderChange"
              >
                <el-option
                  v-for="p in providerList"
                  :key="String(p.id)"
                  :label="p.providerName + ' (' + p.providerType + ')'"
                  :value="String(p.id)"
                />
              </el-select>
            </el-form-item>

            <!-- Dynamic Model Selection from Model Center -->
            <el-form-item label="超分模型 (AI Model)">
              <div class="space-y-1 w-full">
                <el-select
                  v-model="formData.modelId"
                  placeholder="请选择超分模型"
                  class="w-full"
                  :loading="loadingModels"
                  @change="handleModelChange"
                >
                  <el-option
                    v-for="m in modelList"
                    :key="String(m.id)"
                    :label="m.modelName + ' (' + m.modelCode + ')'"
                    :value="String(m.id)"
                  />
                </el-select>

                <div v-if="!loadingModels && modelList.length === 0" class="text-[11px] text-amber-500 pt-0.5">
                  ⚠️ 当前提供商未启用任何 VIDEO_UPSCALE 类型的模型，请前往「AI 提供商与模型管理」页面配置
                </div>
              </div>
            </el-form-item>

            <!-- Scale & Source FPS -->
            <div class="grid grid-cols-2 gap-3">
              <el-form-item label="超分放大倍率">
                <!-- If model limits scales, render select -->
                <el-select
                  v-if="availableScales.length > 0"
                  v-model="formData.scale"
                  class="w-full"
                >
                  <el-option
                    v-for="s in availableScales"
                    :key="s"
                    :value="s"
                    :label="`${s}× 无损放大`"
                  />
                </el-select>
                <el-input-number
                  v-else
                  v-model="formData.scale"
                  :min="2"
                  :max="4"
                  :step="1"
                  class="!w-full"
                />
              </el-form-item>

              <el-form-item label="输出视频帧率 (FPS)">
                <el-input-number v-model="formData.sourceFps" :min="1" :max="60" class="!w-full" />
              </el-form-item>
            </div>

            <!-- CRF Slider -->
            <el-form-item>
              <template #label>
                <div class="flex items-center justify-between">
                  <span>画质质量参数 (CRF)</span>
                  <span class="font-mono text-indigo-400">{{ formData.crf }} (推荐 16)</span>
                </div>
              </template>
              <el-slider
                v-model="formData.crf"
                :min="crfRange.min"
                :max="crfRange.max"
                :step="1"
                show-stops
              />
              <div class="text-[11px] text-[var(--text-secondary)] mt-1">
                数值越小画质越细腻清晰，体积相对增大；16 为高质量超分压制黄金平衡点
              </div>
            </el-form-item>

            <!-- Preserve Audio Switch -->
            <el-form-item label="保持原声音频">
              <div class="flex items-center justify-between w-full">
                <span class="text-xs text-[var(--text-secondary)]">合成时自动透传源视频伴奏与对白音轨</span>
                <el-switch v-model="formData.preserveAudio" active-text="保留" inactive-text="静音" />
              </div>
            </el-form-item>

            <!-- Submit Action -->
            <div class="pt-2">
              <el-button
                type="primary"
                class="w-full !h-10 !text-sm font-semibold shadow-lg shadow-indigo-500/20"
                :loading="submitting"
                :disabled="isSubmitDisabled"
                @click="handleSubmit"
              >
                <el-icon class="mr-1.5"><Promotion /></el-icon>
                提交超分增强任务 ({{ selectedModel?.modelName || '超分' }})
              </el-button>
            </div>
          </el-form>
        </el-card>
      </div>

      <!-- Right Column: Live Execution & Player (7 cols) -->
      <div class="lg:col-span-7 space-y-5">
        <!-- Active Task Banner -->
        <el-card
          v-if="currentTask && (currentTask.status === 'QUEUED' || currentTask.status === 'PROCESSING')"
          shadow="never"
          class="!bg-indigo-950/20 !border-indigo-500/30 !rounded-xl"
        >
          <div class="flex items-center justify-between mb-3">
            <div class="flex items-center gap-2">
              <el-icon class="text-indigo-400 is-loading text-base"><Loading /></el-icon>
              <span class="font-bold text-sm text-[var(--text-primary)]">
                任务正在执行中 ({{ currentTask.taskId }})
              </span>
            </div>
            <div class="flex items-center gap-2">
              <el-tag size="small" type="primary" effect="dark">{{ currentTask.currentNode || '处理中' }}</el-tag>
              <el-button size="small" type="danger" plain @click="handleCancelTask(currentTask.taskId)">
                取消任务
              </el-button>
            </div>
          </div>
          <el-progress
            :percentage="currentTask.progress"
            :stroke-width="8"
            striped
            striped-flow
          />
          <div class="text-[11px] text-[var(--text-secondary)] mt-2 flex justify-between">
            <span>当前阶段：{{ currentTask.currentNode }}</span>
            <span>模型：{{ currentTask.modelCode }}</span>
          </div>
        </el-card>

        <!-- Video Player & Comparison Card -->
        <el-card shadow="never" class="!bg-[var(--surface)] !border-[var(--border-default)] !rounded-xl">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-semibold text-sm flex items-center gap-2">
                <el-icon class="text-indigo-400"><VideoCamera /></el-icon> 视听对比与成片预览
              </span>
              <el-radio-group v-model="activeViewMode" size="small">
                <el-radio-button value="enhanced">✨ 超分高清产物</el-radio-button>
                <el-radio-button value="original">📼 原始输入视频</el-radio-button>
              </el-radio-group>
            </div>
          </template>

          <div class="space-y-4">
            <!-- Video Display Container -->
            <div class="w-full aspect-video bg-black/60 rounded-lg overflow-hidden flex items-center justify-center border border-[var(--border-default)] relative group">
              <video
                v-if="displayVideoUrl"
                :key="displayVideoUrl"
                :src="displayVideoUrl"
                controls
                autoplay
                loop
                class="w-full h-full object-contain"
              />
              <div v-else class="text-center text-slate-500 py-12 flex flex-col items-center justify-center">
                <el-icon class="text-5xl mb-2 text-slate-600"><Film /></el-icon>
                <p class="text-xs">暂无播放内容，请在左侧选择源视频并启动超分任务</p>
              </div>

              <!-- Quality Watermark Badge -->
              <div v-if="displayVideoUrl" class="absolute top-3 right-3 px-2 py-1 rounded bg-black/70 backdrop-blur text-[10px] font-mono text-white pointer-events-none">
                {{ activeViewMode === 'enhanced' ? `✨ ${selectedModel?.modelName || 'Enhanced'}` : '📼 Original Source' }}
              </div>
            </div>

            <!-- Details & Actions Bar -->
            <div v-if="currentTask && currentTask.outputVideoUrl" class="p-3 rounded-lg bg-[var(--surface-muted)] border border-[var(--border-default)] flex items-center justify-between flex-wrap gap-2">
              <div class="text-xs space-y-0.5">
                <div class="font-semibold text-[var(--text-primary)] flex items-center gap-2">
                  <span>{{ currentTask.taskId }}</span>
                  <el-tag size="small" type="success" effect="plain">超分成功</el-tag>
                  <el-tag v-if="currentTask.sourceShotId" size="small" type="primary" effect="plain">
                    S{{ currentTask.sourceShotNo }} {{ currentTask.sourceShotName || '分镜' }}
                  </el-tag>
                </div>
                <div class="text-[11px] text-[var(--text-secondary)] font-mono">
                  耗时: {{ Math.round((currentTask.costMs || 0) / 1000) }}s |
                  输出: {{ currentTask.width || '未知' }}×{{ currentTask.height || '未知' }} |
                  CRF: {{ currentTask.crf }}
                </div>
              </div>
              <div class="flex items-center gap-2">
                <!-- 已自动保存状态标签 -->
                <el-tag
                  v-if="currentTask.savedTakeNo"
                  size="small"
                  type="success"
                  effect="dark"
                  class="flex items-center gap-1 font-medium !py-1"
                >
                  <span>✓ 已自动录入 Take #{{ currentTask.savedTakeNo }}</span>
                  <span v-if="currentTask.isCurrentTake">(当前生效)</span>
                </el-tag>

                <!-- 保存为分镜历史 (来源为分镜时展示) -->
                <el-popover
                  v-if="currentTask.sourceShotId"
                  placement="top-end"
                  :width="300"
                  trigger="click"
                >
                  <template #reference>
                    <el-button
                      size="small"
                      :type="currentTask.savedTakeNo ? 'info' : 'success'"
                      :plain="!!currentTask.savedTakeNo"
                      :loading="savingToShotTaskId === currentTask.taskId"
                    >
                      <el-icon class="mr-1"><Film /></el-icon>
                      {{ currentTask.savedTakeNo ? '重新录入为新 Take' : '保存为分镜历史' }}
                    </el-button>
                  </template>
                  <div class="space-y-3 p-1">
                    <div class="text-xs font-semibold text-[var(--text-primary)] flex items-center gap-1.5">
                      <span>🎬 录入分镜版本库 (S{{ currentTask.sourceShotNo }})</span>
                    </div>
                    <div class="text-[11px] text-[var(--text-secondary)]">
                      将本次超分高清成片录入为该分镜的候选 Take 历史版本。
                    </div>
                    <div class="pt-1 border-t border-[var(--border-default)]">
                      <el-checkbox v-model="saveOptionSetAsCurrent" size="small">
                        设为分镜当前视频 (默认选择)
                      </el-checkbox>
                    </div>
                    <div class="flex justify-end pt-1">
                      <el-button
                        size="small"
                        type="primary"
                        :loading="savingToShotTaskId === currentTask.taskId"
                        @click="handleSaveToShot(currentTask, saveOptionSetAsCurrent)"
                      >
                        确认执行保存
                      </el-button>
                    </div>
                  </div>
                </el-popover>

                <el-button size="small" type="primary" plain @click="downloadVideo(currentTask.outputVideoUrl)">
                  <el-icon class="mr-1"><Download /></el-icon> 下载成片
                </el-button>
                <el-button size="small" plain @click="copyUrl(currentTask.outputVideoUrl)">
                  <el-icon class="mr-1"><CopyDocument /></el-icon> 复制 URL
                </el-button>
              </div>
            </div>
          </div>
        </el-card>
      </div>
    </div>

    <!-- History Tasks Dialog -->
    <el-dialog v-model="historyDrawer" title="视频超分历史记录" width="min(960px, 92vw)" top="5vh" destroy-on-close>
      <div class="space-y-4">
        <div class="flex items-center justify-between">
          <span class="text-xs text-[var(--text-secondary)]">展示最近提交的超分任务及对应源</span>
          <el-button size="small" plain @click="loadHistoryTasks" :loading="loadingHistory">刷新</el-button>
        </div>

        <el-table :data="historyTasks" size="small" border class="w-full">
          <el-table-column prop="taskId" label="任务ID" width="150" show-overflow-tooltip />

          <!-- 来源列 -->
          <el-table-column label="源分镜/来源" width="160">
            <template #default="{ row }">
              <div v-if="row.sourceType === 'SHOT_CURRENT' || row.sourceType === 'SHOT_VIDEO_TAKE'" class="space-y-0.5">
                <div class="flex items-center gap-1 font-mono text-[11px] font-bold text-indigo-600">
                  <span>S{{ row.sourceShotNo }}</span>
                  <span class="font-normal text-slate-700 truncate max-w-[90px]">{{ row.sourceShotName }}</span>
                </div>
                <div class="text-[10px] text-slate-400 truncate">
                  {{ row.sourceType === 'SHOT_CURRENT' ? '当前视频' : 'Take 历史' }}
                </div>
              </div>
              <span v-else class="text-[11px] text-slate-400">上传 / URL</span>
            </template>
          </el-table-column>

          <el-table-column prop="status" label="状态" width="85">
            <template #default="{ row }">
              <el-tag
                size="small"
                :type="row.status === 'SUCCESS' ? 'success' : (row.status === 'FAILED' ? 'danger' : 'primary')"
              >
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="75">
            <template #default="{ row }">
              {{ row.costMs ? (row.costMs / 1000).toFixed(1) + 's' : '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="submitTime" label="提交时间" min-width="135" show-overflow-tooltip />
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="selectTask(row)">载入预览</el-button>
              <el-tag
                v-if="row.savedTakeNo"
                size="small"
                type="success"
                effect="plain"
                class="mx-1"
              >
                Take #{{ row.savedTakeNo }}
              </el-tag>
              <el-button
                v-else-if="row.status === 'SUCCESS' && row.outputVideoUrl && row.sourceShotId"
                size="small"
                link
                type="success"
                :loading="savingToShotTaskId === row.taskId"
                @click="handleSaveToShot(row, true)"
              >
                保存为历史
              </el-button>
              <el-button
                v-if="row.status === 'PROCESSING' || row.status === 'QUEUED'"
                size="small"
                link
                type="danger"
                @click="handleCancelTask(row.taskId)"
              >
                取消
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <!-- Shot Video Picker Dialog Component -->
    <ShotVideoPickerDialog
      ref="shotPickerRef"
      @selected="handleShotVideoSelected"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ZoomIn,
  Film,
  Setting,
  UploadFilled,
  CircleCheckFilled,
  Loading,
  Promotion,
  VideoCamera,
  Download,
  CopyDocument,
  Refresh,
  List,
  FolderOpened
} from '@element-plus/icons-vue'
import { assetApi } from '@/api/res-asset'
import { shotApi } from '@/api/drama'
import { aiProviderApi } from '@/api/ai-provider'
import type { AiProviderVO } from '@/types/ai-provider'
import {
  submitVideoProcessTask,
  getVideoProcessTask,
  getVideoProcessTasks,
  cancelVideoProcessTask,
  probeVideoInfo,
  probeVideoSource,
  getVideoProcessingModels,
  saveVideoProcessToShot,
  type VideoProcessResultVO,
  type VideoProbeInfoVO,
  type VideoProcessingModelOption,
  type VideoProcessSubmitDTO
} from '@/api/videoProcessing'
import ShotVideoPickerDialog, { type SelectedVideoProcessSource } from './components/ShotVideoPickerDialog.vue'

// Source Input Modes
type SourceInputMode = 'SHOT' | 'UPLOAD' | 'URL'
const sourceInputMode = ref<SourceInputMode>('SHOT')
const sourceModeOptions = [
  { label: '🎞️ 从分镜选择', value: 'SHOT' },
  { label: '📤 本地上传', value: 'UPLOAD' },
  { label: '🔗 URL 直连 (高级)', value: 'URL' }
]

// State
const uploading = ref(false)
const probing = ref(false)
const submitting = ref(false)
const loadingHistory = ref(false)
const loadingModels = ref(false)
const historyDrawer = ref(false)

const providerList = ref<AiProviderVO[]>([])
const modelList = ref<VideoProcessingModelOption[]>([])
const selectedSource = ref<SelectedVideoProcessSource | null>(null)
const route = useRoute()
const shotPickerRef = ref<InstanceType<typeof ShotVideoPickerDialog> | null>(null)

const videoProbe = ref<VideoProbeInfoVO | null>(null)
const currentTask = ref<VideoProcessResultVO | null>(null)
const historyTasks = ref<VideoProcessResultVO[]>([])
const totalTasks = ref(0)
const activeViewMode = ref<'enhanced' | 'original'>('enhanced')

const formData = reactive({
  operation: 'VIDEO_UPSCALE' as const,
  sourceType: 'SHOT_CURRENT' as 'DIRECT_URL' | 'SHOT_CURRENT' | 'SHOT_VIDEO_TAKE',
  sourceVideoUrl: '',
  sourceShotId: '' as string,
  sourceVideoTakeId: '' as string,
  providerId: '',
  modelId: '',
  scale: 2,
  sourceFps: 24,
  targetFps: 24,
  crf: 16,
  preserveAudio: true,
  autoSaveToShot: true,
  setAsCurrent: true
})

// Computed current selected model
const selectedModel = computed(() => {
  return modelList.value.find(m => String(m.id) === String(formData.modelId)) || null
})

// Available scales from model config
const availableScales = computed(() => {
  return selectedModel.value?.config?.allowedScales || [2, 4]
})

// CRF Slider range from model config
const crfRange = computed(() => {
  return {
    min: selectedModel.value?.config?.minCrf ?? 10,
    max: selectedModel.value?.config?.maxCrf ?? 30
  }
})

// Submit button disabled check
const isSubmitDisabled = computed(() => {
  if (!formData.modelId) return true
  if (sourceInputMode.value === 'SHOT') {
    return !formData.sourceShotId
  }
  return !formData.sourceVideoUrl
})

// Display video URL computed
const displayVideoUrl = computed(() => {
  if (activeViewMode.value === 'original') {
    return formData.sourceVideoUrl
  }
  return currentTask.value?.outputVideoUrl || formData.sourceVideoUrl
})

/**
 * 切换源输入模式
 */
function handleSourceModeChange(mode: SourceInputMode) {
  if (mode === 'SHOT') {
    if (selectedSource.value) {
      formData.sourceType = selectedSource.value.sourceType
      formData.sourceShotId = selectedSource.value.sourceShotId
      formData.sourceVideoTakeId = selectedSource.value.sourceVideoTakeId || ''
      formData.sourceVideoUrl = selectedSource.value.sourceVideoUrl
    } else {
      formData.sourceType = 'SHOT_CURRENT'
      formData.sourceShotId = ''
      formData.sourceVideoTakeId = ''
      formData.sourceVideoUrl = ''
    }
  } else {
    formData.sourceType = 'DIRECT_URL'
    formData.sourceShotId = ''
    formData.sourceVideoTakeId = ''
  }
  videoProbe.value = null
}

/**
 * 打开分镜选择器
 */
function openShotPicker() {
  shotPickerRef.value?.open(selectedSource.value?.sourceDramaId)
}

/**
 * 清除已选分镜
 */
function clearSelectedSource() {
  selectedSource.value = null
  formData.sourceShotId = ''
  formData.sourceVideoTakeId = ''
  formData.sourceVideoUrl = ''
  videoProbe.value = null
}

/**
 * 分镜视频选择回调
 */
async function handleShotVideoSelected(source: SelectedVideoProcessSource) {
  selectedSource.value = source
  formData.sourceType = source.sourceType
  formData.sourceShotId = source.sourceShotId
  formData.sourceVideoTakeId = source.sourceVideoTakeId || ''
  formData.sourceVideoUrl = source.sourceVideoUrl
  currentTask.value = null
  videoProbe.value = null
  activeViewMode.value = 'original'
  await triggerProbeForSelectedSource()
}

watch(() => route.query.shotId, async (queryShotId) => {
  const shotId = Array.isArray(queryShotId) ? queryShotId[0] : queryShotId
  if (!shotId) return
  try {
    const shot = await shotApi.getById(shotId)
    if (!shot?.videoUrl) {
      ElMessage.warning('该分镜尚无可处理的视频')
      return
    }
    sourceInputMode.value = 'SHOT'
    formData.autoSaveToShot = true
    formData.setAsCurrent = true
    await handleShotVideoSelected({
      sourceType: 'SHOT_CURRENT',
      sourceVideoUrl: shot.videoUrl,
      sourceDramaId: String(shot.dramaId),
      sourceEpisodeId: shot.episodeId ? String(shot.episodeId) : undefined,
      sourceSceneId: shot.sceneId ? String(shot.sceneId) : undefined,
      sourceShotId: String(shot.id),
      shotNo: shot.shotNo,
      shotName: shot.shotName,
      posterUrl: shot.previewImageUrl
    })
  } catch (err: any) {
    ElMessage.error(err?.message || '加载分镜视频失败')
  }
}, { immediate: true })

/**
 * 针对所选分镜源进行统一探测
 */
async function triggerProbeForSelectedSource() {
  if (!formData.sourceShotId) return
  probing.value = true
  try {
    const info = await probeVideoSource({
      sourceType: formData.sourceType,
      sourceShotId: formData.sourceShotId,
      sourceVideoTakeId: formData.sourceVideoTakeId || undefined
    })
    videoProbe.value = info
    if (info && info.fps) {
      const parsedFps = Math.round(info.fps)
      formData.sourceFps = parsedFps
      formData.targetFps = parsedFps
    }
  } catch (err) {
    console.warn('Probe video source failed:', err)
  } finally {
    probing.value = false
  }
}

/**
 * 加载已启用 AI 提供商
 */
async function loadProviders() {
  try {
    const list = await aiProviderApi.getListEnabled()
    providerList.value = list || []
    if (providerList.value.length > 0 && !formData.providerId) {
      formData.providerId = String(providerList.value[0].id)
      await loadModelsForProvider(formData.providerId)
    }
  } catch (err) {
    console.error('Failed to load AI providers:', err)
  }
}

/**
 * 提供商切换
 */
async function handleProviderChange() {
  formData.modelId = ''
  if (formData.providerId) {
    await loadModelsForProvider(formData.providerId)
  } else {
    modelList.value = []
  }
}

/**
 * 获取指定提供商下的 VIDEO_UPSCALE 模型
 */
async function loadModelsForProvider(providerId: string | number) {
  loadingModels.value = true
  try {
    const models = await getVideoProcessingModels(String(providerId), 'VIDEO_UPSCALE')
    modelList.value = models || []
    if (models.length > 0) {
      formData.modelId = String(models[0].id)
      applyModelDefaults(models[0])
    }
  } catch (err) {
    console.error('Failed to load video upscale models:', err)
  } finally {
    loadingModels.value = false
  }
}

/**
 * 模型切换
 */
function handleModelChange() {
  if (selectedModel.value) {
    applyModelDefaults(selectedModel.value)
  }
}

/**
 * 应用模型中心的安全默认配置
 */
function applyModelDefaults(model: VideoProcessingModelOption) {
  if (model.config) {
    if (model.config.defaultScale) {
      formData.scale = model.config.defaultScale
    } else if (model.config.allowedScales && model.config.allowedScales.length > 0) {
      formData.scale = model.config.allowedScales[0]
    }
    if (model.config.defaultCrf !== undefined) {
      formData.crf = model.config.defaultCrf
    }
    if (model.config.defaultPreserveAudio !== undefined) {
      formData.preserveAudio = model.config.defaultPreserveAudio
    }
  }
}

/**
 * 本地视频上传至 MinIO
 */
async function handleVideoUpload(options: { file: File }) {
  uploading.value = true
  try {
    const res = await assetApi.upload(options.file, 'video')
    if (res && res.url) {
      formData.sourceVideoUrl = res.url
      ElMessage.success('视频上传成功，正在探测元数据...')
      await triggerProbe()
    }
  } catch (err: any) {
    ElMessage.error(err?.message || '视频上传失败')
  } finally {
    uploading.value = false
  }
}

function handleUrlChange() {
  if (formData.sourceVideoUrl) {
    triggerProbe()
  }
}

/**
 * 探测 URL 视频元数据
 */
async function triggerProbe() {
  if (!formData.sourceVideoUrl) return
  probing.value = true
  try {
    const info = await probeVideoInfo(formData.sourceVideoUrl)
    videoProbe.value = info
    if (info && info.fps) {
      const parsedFps = Math.round(info.fps)
      formData.sourceFps = parsedFps
      formData.targetFps = parsedFps
    }
  } catch (err) {
    console.warn('Probe failed:', err)
  } finally {
    probing.value = false
  }
}

/**
 * 提交任务
 */
async function handleSubmit() {
  if (sourceInputMode.value === 'SHOT' && !formData.sourceShotId) {
    ElMessage.warning('请先从短剧分镜库选择源视频')
    return
  }
  if (sourceInputMode.value !== 'SHOT' && !formData.sourceVideoUrl) {
    ElMessage.warning('请先提供源视频文件或 URL')
    return
  }
  if (!formData.modelId) {
    ElMessage.warning('请选择有效的超分模型')
    return
  }

  submitting.value = true
  try {
    const payload: VideoProcessSubmitDTO = {
      operation: 'VIDEO_UPSCALE',
      sourceType: formData.sourceType,
      sourceShotId: formData.sourceType !== 'DIRECT_URL' ? formData.sourceShotId : undefined,
      sourceVideoTakeId: formData.sourceType === 'SHOT_VIDEO_TAKE' ? formData.sourceVideoTakeId : undefined,
      sourceVideoUrl: formData.sourceType === 'DIRECT_URL' ? formData.sourceVideoUrl.trim() : undefined,
      providerId: formData.providerId ? String(formData.providerId) : undefined,
      modelId: String(formData.modelId),
      sourceFps: formData.sourceFps,
      targetFps: formData.sourceFps,
      scale: formData.scale,
      crf: formData.crf,
      preserveAudio: formData.preserveAudio,
      autoSaveToShot: formData.sourceType !== 'DIRECT_URL' ? formData.autoSaveToShot : false,
      setAsCurrent: formData.sourceType !== 'DIRECT_URL' ? formData.setAsCurrent : false
    }

    const result = await submitVideoProcessTask(payload)
    currentTask.value = result
    activeViewMode.value = 'enhanced'
    ElMessage.success('视频超分任务已提交，正在排队执行！')
    startPolling(result.taskId)
    loadHistoryTasks()
  } catch (err: any) {
    ElMessage.error(err?.message || '提交超分任务失败')
  } finally {
    submitting.value = false
  }
}

// 轮询任务状态
let pollTimer: any = null
function startPolling(taskId: string) {
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      const task = await getVideoProcessTask(taskId)
      currentTask.value = task
      if (task.status === 'SUCCESS') {
        stopPolling()
        if (task.savedTakeNo) {
          ElMessage.success(`🎉 视频超分处理完成，并已自动录入为分镜 Take #${task.savedTakeNo}${task.isCurrentTake ? ' (已设为当前镜头视频)' : ''}！`)
        } else {
          ElMessage.success('🎉 视频超分处理完成！')
        }
        activeViewMode.value = 'enhanced'
        loadHistoryTasks()
      } else if (task.status === 'FAILED') {
        stopPolling()
        ElMessage.error('视频超分执行失败: ' + (task.errorMessage || '未知异常'))
        loadHistoryTasks()
      } else if (task.status === 'CANCELLED') {
        stopPolling()
        ElMessage.info('任务已取消')
        loadHistoryTasks()
      }
    } catch (e) {
      console.warn('Polling task status error:', e)
    }
  }, 2500)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// 取消任务
async function handleCancelTask(taskId: string) {
  try {
    await cancelVideoProcessTask(taskId)
    ElMessage.info('已请求取消任务')
    if (currentTask.value && currentTask.value.taskId === taskId) {
      currentTask.value.status = 'CANCELLED'
    }
    stopPolling()
    loadHistoryTasks()
  } catch (err: any) {
    ElMessage.error(err?.message || '取消任务失败')
  }
}

// 加载历史任务列表
async function loadHistoryTasks() {
  loadingHistory.value = true
  try {
    const res = await getVideoProcessTasks({
      operation: 'VIDEO_UPSCALE',
      current: 1,
      size: 20
    })
    historyTasks.value = res.records || []
    totalTasks.value = res.total || 0
  } catch (err) {
    console.error('Failed to load history tasks:', err)
  } finally {
    loadingHistory.value = false
  }
}

function selectTask(task: VideoProcessResultVO) {
  currentTask.value = task
  formData.sourceVideoUrl = task.sourceVideoUrl
  activeViewMode.value = 'enhanced'
  historyDrawer.value = false
}

function downloadVideo(url?: string) {
  if (!url) return
  window.open(url, '_blank')
}

function copyUrl(url?: string) {
  if (!url) return
  navigator.clipboard.writeText(url)
  ElMessage.success('已复制视频地址到剪贴板')
}

// 保存后处理成片到分镜历史
const savingToShotTaskId = ref<string | null>(null)
const saveOptionSetAsCurrent = ref(true)

async function handleSaveToShot(task: VideoProcessResultVO, setAsCurrent = true) {
  if (!task || !task.taskId) return
  if (!task.sourceShotId) {
    ElMessage.warning('该任务无关联源分镜，无法直接录入分镜历史')
    return
  }
  savingToShotTaskId.value = task.taskId
  try {
    await saveVideoProcessToShot(task.taskId, {
      shotId: task.sourceShotId,
      setAsCurrent
    })
    ElMessage.success(setAsCurrent ? '🎉 已成功录入为分镜历史 Take 并设为当前视频！' : '🎉 已成功录入为分镜历史 Take！')
  } catch (err: any) {
    ElMessage.error(err?.message || '保存到分镜历史失败')
  } finally {
    savingToShotTaskId.value = null
  }
}


onMounted(() => {
  loadProviders()
  loadHistoryTasks()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.video-uploader :deep(.el-upload-dragger) {
  background-color: var(--surface-muted);
  border: 1px dashed var(--border-default);
  border-radius: var(--radius-md, 8px);
  padding: 10px;
}
.video-uploader :deep(.el-upload-dragger:hover) {
  border-color: var(--brand);
}
</style>
