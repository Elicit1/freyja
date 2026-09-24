<template>
  <FullScreenPage
    v-model="visible"
    scope="workspace"
    breadcrumb="剧作大纲与分镜 / 分镜精细工坊"
    :title="isEdit ? `分镜精细配置 - ${form.shotName || '镜头'}` : '新增分镜镜头'"
    max-width="max-w-6xl"
  >
    <template #badge>
      <StatusPill
        :status="form.renderStatus || 'disabled'"
        :label="getStatusLabel(form.renderStatus)"
        size="small"
      />
      <span v-if="form.shotNo" class="studio-badge bg-[var(--surface-muted)] text-[var(--text-secondary)] border border-[var(--border-default)] font-mono !py-0 !px-1.5 !text-[10px]">
        镜序 #{{ form.shotNo }}
      </span>
      <span v-if="form.renderStatus === 'RENDERING'" class="text-xs text-[var(--brand)] flex items-center gap-1 font-medium">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>{{ form.currentNode || '渲染生成中...' }}</span>
      </span>
    </template>

    <template #actions>
      <el-button v-if="isEdit" type="danger" plain size="small" @click="handleDelete">
        删除镜头
      </el-button>
      <el-button size="small" @click="visible = false">取消</el-button>
      <el-button v-if="isEdit" size="small" plain type="primary" @click="historyDrawerVisible = true">
        🎞️ 查看渲染历史
      </el-button>
      <el-button type="primary" size="small" :loading="saving" @click="handleSave(false)">
        保存配置
      </el-button>
      <el-button type="success" size="small" :loading="rendering" @click="handleRender">
        <el-icon class="mr-1"><VideoCamera /></el-icon> 提交渲染
      </el-button>
      <el-button v-if="isEdit" size="small" :disabled="!form.videoUrl" @click="openVideoProcessing('VideoUpscale')">视频超分</el-button>
      <el-button v-if="isEdit" size="small" :disabled="!form.videoUrl" @click="openVideoProcessing('FrameInterpolation')">视频插帧</el-button>
    </template>

    <div v-loading="loading" class="flex flex-col gap-5 pb-6">
      <!-- 顶部状态栏与生成模式 -->
      <div class="studio-card p-4 flex flex-col gap-3">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="text-xs font-bold text-[var(--text-secondary)]">渲染状态:</span>
            <StatusPill
              :status="form.renderStatus || 'disabled'"
              :label="getStatusLabel(form.renderStatus)"
            />
            <span v-if="form.renderStatus === 'RENDERING'" class="text-xs text-[var(--brand)] flex items-center gap-1 font-medium">
              <el-icon class="is-loading"><Loading /></el-icon>
              <span>{{ form.currentNode || '渲染生成中...' }}</span>
            </span>
          </div>
        </div>

        <!-- 镜头画面生成模式分类选择 -->
        <div class="flex items-center justify-between pt-2 border-t border-[var(--border-default)]">
          <div class="flex items-center gap-2">
            <span class="text-xs font-bold text-[var(--text-secondary)]">生成模式:</span>
            <el-radio-group v-model="form.generationMode" size="small">
              <el-radio-button value="FIRST_LAST_FRAME">
                <span>首尾帧模式</span>
              </el-radio-button>
              <el-radio-button value="REFERENCE_MODE">
                <span>参考图与音频模式</span>
              </el-radio-button>
            </el-radio-group>
          </div>

          <!-- 首尾帧模式时的 ⚠️ 叹号提示与 Popover -->
          <el-popover
            v-if="form.generationMode === 'FIRST_LAST_FRAME'"
            placement="bottom"
            :width="300"
            trigger="hover"
          >
            <template #reference>
              <div class="flex items-center gap-1 text-xs font-bold text-amber-700 bg-amber-50 hover:bg-amber-100 border border-amber-200 px-2 py-0.5 rounded cursor-pointer transition-colors">
                <span>⚠️</span>
                <span>仅支持首尾帧与提示词</span>
              </div>
            </template>
            <div class="text-xs text-gray-700 space-y-1">
              <div class="font-bold text-amber-800 flex items-center gap-1">
                <span>⚠️ 首尾帧模式特性提示</span>
              </div>
              <p class="leading-relaxed">
                本模式<strong>仅支持 首帧图、尾帧图、提示词</strong>作为参考。
              </p>
              <p class="text-gray-500 text-[11px] leading-relaxed">
                不强制传输首尾帧，可仅凭提示词直接生成。若上一镜已生成，可选择上一镜尾帧作为该镜头的首帧图。
              </p>
            </div>
          </el-popover>

          <!-- 参考图与音频模式徽章 -->
          <div v-else class="text-xs text-sky-700 bg-sky-50 border border-sky-200 px-2 py-0.5 rounded font-medium">
            <span>🖼️ 参考图≤5张 · 🎵 音频≤4段 (3~8s，总长20~30s)</span>
          </div>
        </div>
      </div>

      <!-- 表单主体 -->
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" label-position="top">
        <!-- 基础规格卡片 -->
        <div class="studio-card p-5">
          <h4 class="text-sm font-bold text-[var(--text-primary)] border-b border-[var(--border-default)] pb-2.5 mb-4 flex items-center gap-2">
            <el-icon class="text-[var(--brand)]"><VideoCamera /></el-icon> 镜头基本规格与机位
          </h4>
          <el-row :gutter="16">
            <el-col :span="4">
              <el-form-item label="镜头序号" prop="shotNo">
                <el-input-number v-model="form.shotNo" :min="1" :max="500" class="w-full" />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="镜头标识" prop="shotName">
                <el-input v-model="form.shotName" placeholder="如：S01-01" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="归属连续镜头组 (ShotGroup)">
                <el-select v-model="form.shotGroupId" placeholder="选择镜头组" clearable class="w-full">
                  <el-option
                    v-for="g in groupOptions"
                    :key="g.id"
                    :label="`#${g.groupNo} ${g.name}`"
                    :value="g.id"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="3">
              <el-form-item label="景别" prop="shotType">
                <DictSelect v-model="form.shotType" dict-type="shot_type" placeholder="景别 (选填)" clearable class="w-full" @change="form.shotTypeLocked = isExplicitCameraChoice($event)" />
                <el-checkbox v-model="form.shotTypeLocked" size="small" :disabled="!isExplicitCameraChoice(form.shotType)">将此景别作为导演约束</el-checkbox>
              </el-form-item>
            </el-col>
            <el-col :span="3">
              <el-form-item label="运镜" prop="cameraMovement">
                <DictSelect v-model="form.cameraMovement" dict-type="camera_movement" placeholder="运镜 (选填)" clearable class="w-full" @change="form.cameraMovementLocked = isExplicitCameraChoice($event)" />
                <el-checkbox v-model="form.cameraMovementLocked" size="small" :disabled="!isExplicitCameraChoice(form.cameraMovement)">将此运镜作为导演约束</el-checkbox>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="预估时长 (秒)" prop="duration">
                <el-input-number v-model="form.duration" :min="0.5" :max="15" :step="0.5" :precision="2" class="w-full" />
              </el-form-item>
            </el-col>
            <el-col :span="16">
              <el-form-item label="音效描述">
                <el-input v-model="form.soundEffect" placeholder="如：雷鸣声、急促脚步声" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="镜头剧本文本 (Shot Script)" prop="scriptContent">
            <el-input
              v-model="form.scriptContent"
              type="textarea"
              :rows="3"
              placeholder="镜头剧本文学台本 (尽量描述上一个画面的相关内容。然后细致刻画表演动作、神态与戏剧细节，供后续AI参考原文与剧本生成Prompt)..."
            />
          </el-form-item>

          <el-form-item label="画面动作与剧情描述" prop="actionDescription">
            <el-input
              v-model="form.actionDescription"
              type="textarea"
              :rows="2"
              placeholder="镜头内人物的肢体动作、交互、物理动态..."
            />
          </el-form-item>

          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="对白台词">
                <el-input v-model="form.dialogue" type="textarea" :rows="2" placeholder="本镜头台词..." />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="旁白 / 独白">
                <el-input v-model="form.voiceover" type="textarea" :rows="2" placeholder="内心独白或旁白..." />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- TTS 配音生成与试听 -->
          <div v-if="form.dialogue" class="bg-indigo-50/50 border border-indigo-100 rounded-lg p-3 text-xs flex items-center justify-between gap-3 mt-2">
            <div class="flex items-center gap-2 overflow-hidden">
              <span class="text-base">🎙️</span>
              <div class="overflow-hidden">
                <div class="font-medium text-gray-800 flex items-center gap-1.5">
                  <span>{{ form.audioUrl ? 'TTS 配音已就绪' : 'MiMo 角色声音克隆' }}</span>
                  <span v-if="form.audioUrl" class="text-[10px] text-emerald-600 font-mono">✓ 已生成</span>
                </div>
                <div v-if="form.audioUrl" class="text-[10px] text-gray-400 truncate max-w-[280px]" :title="form.audioUrl">
                  {{ form.audioUrl }}
                </div>
                <div v-else class="text-[10px] text-gray-400">
                  自动匹配说话人专属母音，将台词克隆合成为高质量音频
                </div>
              </div>
            </div>

            <div class="flex items-center gap-1.5 flex-shrink-0">
              <el-popover placement="bottom-end" :width="330" trigger="click">
                <template #reference>
                  <el-button size="small" text type="primary" class="!px-1.5">
                    ⚙️ 模型设置
                  </el-button>
                </template>
                <div class="space-y-2.5 p-1 text-xs">
                  <div class="font-bold text-gray-800 pb-1 border-b border-gray-100 flex items-center justify-between">
                    <span>🎙️ TTS 克隆配音模型设置</span>
                    <span class="text-[10px] text-gray-400 font-normal">支持自定义</span>
                  </div>
                  <div>
                    <label class="text-[11px] font-medium text-gray-600 mb-1 block">AI 提供商</label>
                    <el-select
                      v-model="voiceProviderId"
                      placeholder="系统默认提供商"
                      clearable
                      size="small"
                      class="w-full"
                      @change="handleVoiceProviderChange"
                    >
                      <el-option
                        v-for="p in voiceProviders"
                        :key="p.id"
                        :label="p.providerName"
                        :value="p.id"
                      />
                    </el-select>
                  </div>
                  <div>
                    <label class="text-[11px] font-medium text-gray-600 mb-1 block">语音克隆模型代码</label>
                    <el-select
                      v-model="voiceModelCode"
                      placeholder="输入或选择模型代码"
                      clearable
                      filterable
                      allow-create
                      default-first-option
                      size="small"
                      class="w-full"
                    >
                      <el-option
                        v-for="m in voiceModels"
                        :key="m.modelCode"
                        :label="`${m.modelName} (${m.modelCode})`"
                        :value="m.modelCode"
                      />
                      <el-option-group v-if="!voiceModels.some(m => m.modelCode === 'mimo-v2.5-tts-voiceclone')" label="推荐预设模型">
                        <el-option label="MiMo 角色声音克隆 (mimo-v2.5-tts-voiceclone)" value="mimo-v2.5-tts-voiceclone" />
                        <el-option label="CosyVoice 声音克隆 (cosyvoice-v1)" value="cosyvoice-v1" />
                        <el-option label="F5-TTS 声音克隆 (f5-tts)" value="f5-tts" />
                      </el-option-group>
                    </el-select>
                  </div>
                  <div>
                    <label class="text-[11px] font-medium text-gray-600 mb-1 block">情绪与语气提示 (选填)</label>
                    <el-input
                      v-model="voiceEmotion"
                      placeholder="如：语气急促紧张、压抑怒火、带有哭腔..."
                      size="small"
                      clearable
                    />
                  </div>
                </div>
              </el-popover>
              <el-button
                v-if="form.audioUrl"
                size="small"
                type="success"
                plain
                @click="togglePlayDrawerAudio"
              >
                {{ isPlayingDrawerAudio ? '⏸ 暂停' : '▶ 播放配音' }}
              </el-button>
              <el-button
                size="small"
                type="primary"
                plain
                :loading="generatingDrawerVoice"
                @click="handleGenerateDrawerVoice"
              >
                {{ form.audioUrl ? '重新克隆' : '生成配音' }}
              </el-button>
              <el-button
                v-if="form.audioUrl"
                size="small"
                type="danger"
                text
                @click="handleClearDrawerAudio"
              >
                清除
              </el-button>
            </div>
          </div>
        </div>

        <!-- 角色出场装配卡片 -->
        <div class="studio-card p-5 mt-4">
          <div class="flex items-center justify-between border-b border-[var(--border-default)] pb-2.5 mb-4">
            <h4 class="text-sm font-bold text-[var(--text-primary)] flex items-center gap-2">
              <el-icon class="text-[var(--brand)]"><User /></el-icon> 出场角色与造型装配 (共 {{ characterRefs.length }} 人)
            </h4>
            <div class="flex items-center gap-2">
              <el-button type="primary" link size="small" @click="handleAddCharacter">
                + 添加出场角色
              </el-button>
              <el-button type="success" link size="small" @click="handleSaveAndCreateAsset('character')">
                + 新建角色
              </el-button>
            </div>
          </div>

          <div v-if="characterRefs.length === 0" class="text-center py-6 text-[var(--text-muted)] text-xs bg-[var(--surface-muted)] rounded-xl border border-dashed border-[var(--border-default)]">
            暂无角色绑定，点击上方“+ 添加出场角色”将资产库人物与造型注入此镜头
          </div>

          <div v-else class="flex flex-col gap-3">
            <div
              v-for="(refItem, idx) in characterRefs"
              :key="idx"
              class="border border-[var(--border-default)] rounded-xl p-3.5 bg-[var(--surface-muted)] flex flex-col gap-3 relative"
            >
              <div class="flex items-center justify-between">
                <span class="text-xs font-bold text-[var(--brand)]">角色 {{ idx + 1 }}</span>
                <el-button type="danger" link size="small" @click="handleRemoveCharacter(idx)">
                  移除
                </el-button>
              </div>

              <el-row :gutter="12">
                <el-col :span="12">
                  <el-form-item label="选择角色" class="!mb-0">
                    <el-select
                      v-model="refItem.characterId"
                      placeholder="选择角色"
                      filterable
                      class="w-full"
                      @change="(val: any) => handleCharacterChange(refItem, val)"
                    >
                      <el-option
                        v-for="c in characterOptions"
                        :key="String(c.id)"
                        :label="`${c.name} (${c.roleType || '角色'})`"
                        :value="String(c.id)"
                      />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="选择造型服装" class="!mb-0">
                    <el-select
                      v-model="refItem.lookId"
                      placeholder="选择造型"
                      filterable
                      clearable
                      class="w-full"
                      @change="(val: any) => handleOutfitChange(refItem, val)"
                    >
                      <el-option
                        v-for="o in getOutfitsForCharacter(refItem.characterId)"
                        :key="String(o.id)"
                        :label="`${o.lookName} ${o.isDefault === 1 ? '(默认)' : ''}`"
                        :value="String(o.id)"
                      />
                    </el-select>
                  </el-form-item>
                </el-col>
              </el-row>
            </div>
          </div>
        </div>

        <!-- 关键道具装配卡片 -->
        <div class="studio-card p-5 mt-4">
          <div class="flex items-center justify-between border-b border-[var(--border-default)] pb-2.5 mb-4">
            <h4 class="text-sm font-bold text-[var(--text-primary)] flex items-center gap-2">
              <el-icon class="text-purple-600"><Box /></el-icon> 关键道具装配 (共 {{ propRefs.length }} 件)
            </h4>
            <div class="flex items-center gap-2">
              <el-button type="primary" link size="small" @click="handleAddProp">
                + 添加关键道具
              </el-button>
              <el-button type="success" link size="small" @click="handleSaveAndCreateAsset('prop')">
                + 新建道具
              </el-button>
            </div>
          </div>

          <div v-if="propRefs.length === 0" class="text-center py-5 text-[var(--text-muted)] text-xs bg-[var(--surface-muted)] rounded-xl border border-dashed border-[var(--border-default)]">
            暂无道具绑定，点击上方“+ 添加关键道具”关联本剧核心道具资产 (如黄铜座钟、黑色匕首)
          </div>

          <div v-else class="flex flex-col gap-3">
            <div
              v-for="(refItem, idx) in propRefs"
              :key="idx"
              class="border border-[var(--border-default)] rounded-xl p-3 bg-[var(--surface-muted)] flex flex-col gap-2 relative"
            >
              <div class="flex items-center justify-between">
                <span class="text-xs font-bold text-purple-700">道具 {{ idx + 1 }}</span>
                <el-button type="danger" link size="small" @click="handleRemoveProp(idx)">
                  移除
                </el-button>
              </div>

              <el-form-item label="选择道具" class="!mb-0">
                <el-select
                  v-model="refItem.propId"
                  placeholder="选择道具"
                  filterable
                  class="w-full"
                  @change="(val: any) => handlePropChange(refItem, val)"
                >
                  <el-option
                    v-for="p in propOptions"
                    :key="String(p.id)"
                    :label="`${p.name} (${p.propType || 'KEY_PROP'})`"
                    :value="String(p.id)"
                  />
                </el-select>
              </el-form-item>
            </div>
          </div>
        </div>

        <!-- 场景环境资产覆盖 -->
        <div class="studio-card p-5 mt-4">
          <div class="flex items-center justify-between border-b border-[var(--border-default)] pb-2.5 mb-4">
            <h4 class="text-sm font-bold text-[var(--text-primary)] flex items-center gap-2">
              <el-icon class="text-emerald-600"><Picture /></el-icon> 镜头环境场景
            </h4>
            <el-button type="success" link size="small" @click="handleSaveAndCreateAsset('scene')">
              + 新建场景
            </el-button>
          </div>
          <el-form-item label="环境场景资产">
            <el-select
              v-model="form.resSceneId"
              placeholder="默认继承所属场次设置"
              clearable
              filterable
              class="w-full"
            >
              <el-option
                v-for="s in sceneOptions"
                :key="String(s.id)"
                :label="`${s.name}${s.sceneType || s.timeOfDay ? ` (${[s.sceneType, s.timeOfDay].filter(Boolean).join('/')})` : ''}`"
                :value="String(s.id)"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="自定义场景提示词 (可选覆盖)">
            <el-input
              v-model="form.customScenePrompt"
              type="textarea"
              :rows="2"
              placeholder="如需单独微调此镜头的场景背景可在此输入..."
            />
          </el-form-item>
        </div>

        <!-- 结构化导演决策计划卡片 (DirectorPlan) -->
        <div v-if="parsedDirectorPlan" class="studio-card p-4 mt-4 bg-gradient-to-r from-slate-50 to-indigo-50/30 border-indigo-200">
          <div class="flex items-center justify-between border-b border-indigo-100 pb-2 mb-3">
            <h4 class="text-sm font-bold text-indigo-950 flex items-center gap-1.5">
              <span>🎬</span> 结构化导演决策计划 (DirectorPlan)
            </h4>
            <div class="flex items-center gap-2">
              <el-tag size="small" type="primary" effect="plain">{{ parsedDirectorPlan.shotSize || (form.shotTypeLocked ? form.shotType : 'AUTO') || 'AUTO' }}</el-tag>
              <el-tag size="small" type="info" effect="plain">{{ parsedDirectorPlan.cameraAngle || '未指定' }}</el-tag>
            </div>
          </div>
          <div class="space-y-2 text-xs">
            <div v-if="parsedDirectorPlan.narrativeIntent" class="text-slate-700">
              <span class="font-semibold text-slate-500">叙事戏剧目标:</span> {{ parsedDirectorPlan.narrativeIntent }}
            </div>
            <div v-if="parsedDirectorPlan.cameraBeats && parsedDirectorPlan.cameraBeats.length > 0" class="flex flex-wrap gap-1.5 pt-1">
              <div
                v-for="(beat, bIdx) in parsedDirectorPlan.cameraBeats"
                :key="bIdx"
                class="bg-white border border-indigo-200 rounded px-2 py-1 text-[11px] text-slate-700 flex items-center gap-1 shadow-xs"
              >
                <span class="font-mono text-indigo-700 font-bold">[{{ beat.startSec }}s - {{ beat.endSec }}s]</span>
                <span class="font-medium text-slate-900">{{ beat.movement }}</span>
                <span v-if="beat.direction" class="text-slate-400 font-mono">({{ beat.direction }})</span>
                <span v-if="beat.narrativePurpose" class="text-slate-500 max-w-[150px] truncate">— {{ beat.narrativePurpose }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 提示词工作台 (Prompt Workshop) -->
        <div class="studio-card p-5 mt-4">
          <div class="flex items-center justify-between border-b border-[var(--border-default)] pb-2.5 mb-4">
            <h4 class="text-sm font-bold text-[var(--text-primary)] flex items-center gap-2">
              <el-icon class="text-[var(--brand)]"><MagicStick /></el-icon> 提示词工作台 (Prompt Workshop)
            </h4>
            <div class="flex items-center gap-2">
              <el-button type="primary" size="small" @click="handleOpenPromptDerive">
                <el-icon class="mr-1"><MagicStick /></el-icon> AI 智能生成提示词
              </el-button>
              <el-button type="primary" link size="small" @click="handleOpenPromptPreview">
                预览组装 Prompt
              </el-button>
            </div>
          </div>

          <!-- 提示词 (原运镜提示词，统一重命名为 提示词) -->
          <div class="mb-4 bg-indigo-50/40 p-3 rounded-lg border border-indigo-100">
            <div class="flex items-center justify-between mb-1.5">
              <span class="text-xs font-bold text-indigo-900 flex items-center gap-1">
                <span>🎬</span> 提示词 (Prompt)
              </span>
              <div class="flex items-center gap-2">
                <span class="text-[10px] text-gray-400">注入机位视角、主体动作动力与视觉氛围</span>
                <el-button size="small" link type="primary" @click="copyToClipboard(form.prompt, '提示词已复制')">
                  📋 复制
                </el-button>
              </div>
            </div>
            <el-input
              v-model="form.prompt"
              type="textarea"
              :rows="3"
              placeholder="正向画面与运镜动力提示词..."
            />
          </div>

          <!-- 首帧提示词 (First Frame Prompt - 仅首尾帧模式展示) -->
          <div
            v-if="form.generationMode !== 'REFERENCE_MODE'"
            class="mb-4 bg-blue-50/40 p-3 rounded-lg border border-blue-100"
          >
            <div class="flex items-center justify-between mb-1.5">
              <span class="text-xs font-bold text-blue-900 flex items-center gap-1">
                <span>🌅</span> 首帧提示词 (First Frame Prompt)
              </span>
              <div class="flex items-center gap-2">
                <span class="text-[10px] text-gray-400">首帧专属画面特征与初始机位</span>
                <el-button size="small" link type="primary" @click="copyToClipboard(form.firstFramePrompt, '首帧提示词已复制')">
                  📋 复制
                </el-button>
              </div>
            </div>
            <el-input
              v-model="form.firstFramePrompt"
              type="textarea"
              :rows="2"
              placeholder="首帧特定画面提示词 (如：人物面部特写、晨光侧照、精致服饰)..."
            />
          </div>

          <!-- 尾帧提示词 (End Frame Prompt - 仅首尾帧模式展示) -->
          <div
            v-if="form.generationMode !== 'REFERENCE_MODE'"
            class="mb-4 bg-purple-50/40 p-3 rounded-lg border border-purple-100"
          >
            <div class="flex items-center justify-between mb-1.5">
              <span class="text-xs font-bold text-purple-900 flex items-center gap-1">
                <span>🏁</span> 尾帧提示词 (End Frame Prompt)
              </span>
              <div class="flex items-center gap-2">
                <span class="text-[10px] text-gray-400">刻画尾帧演变结果状态</span>
                <el-button size="small" link type="primary" @click="copyToClipboard(form.endFramePrompt, '尾帧提示词已复制')">
                  📋 复制
                </el-button>
              </div>
            </div>
            <el-input
              v-model="form.endFramePrompt"
              type="textarea"
              :rows="2"
              placeholder="尾帧特定动态演变提示词 (如：动作停顿、人物转身面对镜头)..."
            />
          </div>

          <!-- 负向提示词 -->
          <el-form-item label="最终负向提示词 (Negative Prompt)" prop="negativePrompt" class="!mb-0">
            <el-input
              v-model="form.negativePrompt"
              type="textarea"
              :rows="2"
              placeholder="负向提示词 (如: low quality, bad hands, blurry)..."
            />
          </el-form-item>
        </div>

        <!-- 参考图与音频多模态管理 (仅当为 REFERENCE_MODE 时展示，位于提示词工作台下方) -->
        <div v-if="form.generationMode === 'REFERENCE_MODE'" class="bg-white border border-sky-200 rounded-xl p-4 shadow-sm mt-4 space-y-4">
          <div class="flex items-center justify-between border-b border-sky-100 pb-2">
            <h4 class="text-sm font-bold text-sky-950 flex items-center gap-1.5">
              <span>🖼️</span> 参考图与音频资产 (多模态模式专属)
            </h4>
            <span class="text-xs text-sky-700">参考图最多 9 张 · 参考音频最多 3 段 (单段 2~15s，总长 ≤ 15s)</span>
          </div>

          <!-- 1. 参考图列表 -->
          <div class="space-y-2">
            <div class="flex items-center justify-between text-xs">
              <span class="font-bold text-gray-700">参考图片列表 ({{ refImages.length }} / 9):</span>
              <div class="flex items-center gap-2">
                <el-dropdown trigger="click" :disabled="refImages.length >= 9" @command="handleSelectAssetCommand">
                  <el-button size="small" type="primary" plain :disabled="refImages.length >= 9">
                    + 从资产库选取
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="scene">🏞️ 场景资产图</el-dropdown-item>
                      <el-dropdown-item command="character">👥 人物造型图</el-dropdown-item>
                      <el-dropdown-item command="prop">🗡️ 道具资产图</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-upload
                  :show-file-list="false"
                  :before-upload="handleUploadRefImage"
                  accept="image/*"
                  :disabled="refImages.length >= 9"
                >
                  <el-button size="small" plain :disabled="refImages.length >= 9" :loading="uploadingRefImg">
                    📤 上传图片
                  </el-button>
                </el-upload>
              </div>
            </div>

            <div v-if="refImages.length === 0" class="text-center py-4 text-xs text-gray-400 bg-gray-50 rounded border border-dashed border-gray-200">
              暂未绑定参考图，点击上方按钮添加 (上限 9 张)
            </div>
            <div v-else class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-2">
              <div
                v-for="(img, idx) in refImages"
                :key="img.id || idx"
                class="border border-slate-200 rounded-lg overflow-hidden bg-white relative group flex flex-col shadow-xs"
              >
                <div class="h-20 bg-slate-900 relative">
                  <el-image :src="img.imageUrl" fit="cover" class="w-full h-full" />
                  <span class="absolute top-1 left-1 bg-black/75 text-white font-mono text-[9px] px-1 py-0.5 rounded">
                    Picture {{ idx + 1 }}
                  </span>
                  <button
                    type="button"
                    class="absolute top-1 right-1 w-4 h-4 rounded-full bg-black/60 text-white hover:bg-red-600 flex items-center justify-center text-[10px] opacity-0 group-hover:opacity-100 transition-opacity"
                    @click="refImages.splice(idx, 1)"
                  >
                    ✕
                  </button>
                </div>
                <div class="p-1 text-[10px] text-gray-700 truncate" :title="img.name">
                  {{ img.name }}
                </div>
                <div class="px-1 pb-1">
                  <el-select v-model="img.usageRole" size="small" placeholder="角色" class="w-full !text-[10px]">
                    <el-option label="主体 (SUBJECT)" value="SUBJECT" />
                    <el-option label="场景 (SCENE)" value="SCENE" />
                    <el-option label="道具 (PROP)" value="PROP" />
                    <el-option label="首帧 (FIRST_FRAME)" value="FIRST_FRAME" />
                    <el-option label="尾帧 (END_FRAME)" value="END_FRAME" />
                  </el-select>
                </div>
              </div>
            </div>
          </div>

          <!-- 2. 参考音频列表 -->
          <div class="space-y-2 pt-2 border-t border-sky-100">
            <div class="flex items-center justify-between text-xs">
              <div class="flex items-center gap-2">
                <span class="font-bold text-gray-700">参考音频列表 ({{ refAudios.length }} / 3):</span>
                <span class="font-mono font-bold" :class="totalAudioDuration <= 15 ? 'text-emerald-600' : 'text-red-600'">
                  总长 {{ totalAudioDuration.toFixed(1) }}s
                </span>
                <span class="text-[10px] text-gray-400">(限制 ≤ 15s)</span>
              </div>
              <div class="flex items-center gap-2">
                <el-button
                  size="small"
                  type="primary"
                  plain
                  :disabled="refAudios.length >= 3"
                  @click="handleOpenCharacterVoicePicker"
                >
                  👥 引入人物库角色配音
                </el-button>
                <el-upload
                  :show-file-list="false"
                  :before-upload="handleUploadRefAudio"
                  accept="audio/*"
                  :disabled="refAudios.length >= 3"
                >
                  <el-button size="small" plain :disabled="refAudios.length >= 3" :loading="uploadingRefAud">
                    📤 上传音频
                  </el-button>
                </el-upload>
              </div>
            </div>

            <div v-if="refAudios.length === 0" class="text-center py-4 text-xs text-gray-400 bg-gray-50 rounded border border-dashed border-gray-200">
              暂未绑定参考音频，点击上方按钮引入人物库角色配音或上传 (上限 3 段，单段 2~15s，总长 ≤ 15s)
            </div>
            <div v-else class="space-y-2">
              <div
                v-for="(aud, aIdx) in refAudios"
                :key="aud.id || aIdx"
                class="bg-slate-50 border border-slate-200 rounded-lg p-2.5 space-y-2 text-xs"
              >
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-2 truncate flex-1 mr-2">
                    <span class="font-mono font-bold text-sky-700 bg-sky-100 px-1.5 py-0.5 rounded text-[11px]">
                      Audio {{ aIdx + 1 }}
                    </span>
                    <span class="font-medium text-slate-800 truncate">{{ aud.name || `音频 ${aIdx + 1}` }}</span>
                    <span class="text-[10px] text-gray-400 font-mono">{{ (aud.duration || 0).toFixed(1) }}s</span>
                    <el-tag size="small" :type="(aud.duration || 0) >= 2 && (aud.duration || 0) <= 15 ? 'success' : 'danger'" class="!text-[9px] !px-1">
                      {{ (aud.duration || 0) >= 2 && (aud.duration || 0) <= 15 ? '✓ 2~15s' : '⚠️ 需2~15s' }}
                    </el-tag>
                  </div>
                  <div class="flex items-center gap-2 flex-shrink-0">
                    <el-button size="small" link type="danger" @click="refAudios.splice(aIdx, 1)">
                      移除
                    </el-button>
                  </div>
                </div>

                <div class="grid grid-cols-1 md:grid-cols-3 gap-2 pt-1 border-t border-slate-200/60 items-center">
                  <div class="flex items-center gap-1.5">
                    <span class="text-[11px] text-gray-500 flex-shrink-0">用途:</span>
                    <el-select v-model="aud.usageMode" size="small" class="w-full">
                      <el-option label="台词复用 (DIALOGUE_REUSE)" value="DIALOGUE_REUSE" />
                      <el-option label="音色参考 (VOICE_TIMBRE)" value="VOICE_TIMBRE" />
                      <el-option label="BGM背景乐 (BGM_REUSE)" value="BGM_REUSE" />
                      <el-option label="环境音 (AMBIENT_REUSE)" value="AMBIENT_REUSE" />
                      <el-option label="特定音效 (SOUND_EFFECT)" value="SOUND_EFFECT" />
                    </el-select>
                  </div>
                  <div class="flex items-center gap-1.5">
                    <span class="text-[11px] text-gray-500 flex-shrink-0">语种:</span>
                    <el-select v-model="aud.language" size="small" class="w-full">
                      <el-option label="中文 (zh)" value="zh" />
                      <el-option label="英文 (en)" value="en" />
                      <el-option label="日文 (ja)" value="ja" />
                      <el-option label="其他 (other)" value="other" />
                    </el-select>
                  </div>
                  <div class="flex items-center gap-1.5">
                    <span class="text-[11px] text-gray-500 flex-shrink-0">台词:</span>
                    <el-input v-model="aud.text" size="small" placeholder="关联台词/文本" clearable />
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 首帧图与尾帧图关键帧设置卡片 (仅首尾帧模式展示，位于提示词工作台下方) -->
        <div
          v-if="form.generationMode !== 'REFERENCE_MODE'"
          class="bg-white border border-gray-200 rounded-xl p-4 shadow-sm mt-4"
        >
          <div class="flex items-center justify-between border-b border-gray-100 pb-2 mb-3">
            <h4 class="text-sm font-bold text-gray-800 flex items-center gap-1.5">
              <span>🖼️</span> 关键帧媒体配置 (首帧图 / 尾帧图)
            </h4>
            <span class="text-xs text-gray-400">均不强制要求，支持空值与纯提示词生成</span>
          </div>

          <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
            <!-- 首帧图卡片 -->
            <div class="border border-slate-200 rounded-lg p-3 bg-slate-50/40 space-y-2">
              <div class="flex items-center justify-between">
                <span class="text-xs font-bold text-indigo-900">首帧图 (First Frame)</span>
                <!-- 按需引用上一镜尾帧快捷入口 -->
                <el-button
                  size="small"
                  type="primary"
                  link
                  class="!text-xs"
                  :loading="inheritingTail"
                  @click="handleApplyPrevTail"
                  title="从同镜头组上一镜视频按需提取尾部稳定帧并引用为本镜首帧"
                >
                  📎 引用上一镜尾帧
                </el-button>
              </div>

              <div class="flex gap-3 items-center">
                <div class="w-24 h-24 bg-slate-900 rounded-lg overflow-hidden border border-slate-300 flex-shrink-0 flex items-center justify-center relative group">
                  <el-image
                    v-if="form.previewImageUrl"
                    :src="form.previewImageUrl"
                    :preview-src-list="[form.previewImageUrl]"
                    preview-teleported
                    fit="cover"
                    class="w-full h-full cursor-pointer"
                  />
                  <span v-else class="text-2xl text-slate-500 select-none">🌅</span>

                  <div v-if="form.previewImageUrl" class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center text-white text-[10px] pointer-events-none">
                    🔍 放大预览
                  </div>
                </div>

                <div class="flex-1 flex flex-col justify-center gap-2">
                  <!-- AI 生成图片与上传按钮等长 -->
                  <el-button
                    size="small"
                    type="primary"
                    class="w-full !bg-indigo-600 !border-indigo-600 hover:!bg-indigo-500"
                    @click="handleOpenAiGenerateFirstFrame"
                  >
                    ✨ AI 生成图片
                  </el-button>
                  <el-upload
                    class="w-full [&_.el-upload]:w-full"
                    :show-file-list="false"
                    :before-upload="handleUploadFirstFrame"
                    accept="image/*"
                  >
                    <el-button size="small" plain class="w-full" :loading="uploadingFirst">
                      📤 上传首帧
                    </el-button>
                  </el-upload>
                  <div v-if="form.previewImageUrl" class="flex justify-end">
                    <el-button
                      size="small"
                      type="danger"
                      link
                      class="!p-0 text-xs"
                      @click="handleClearFirstFrame"
                    >
                      🗑️ 清除首帧
                    </el-button>
                  </div>
                </div>
              </div>
            </div>

            <!-- 尾帧图卡片 -->
            <div class="border border-slate-200 rounded-lg p-3 bg-slate-50/40 space-y-2">
              <div class="flex items-center justify-between">
                <span class="text-xs font-bold text-purple-900">尾帧图 (End Frame)</span>
                <span class="text-[11px] text-gray-400">供首尾关键帧过渡</span>
              </div>

              <div class="flex gap-3 items-center">
                <div class="w-24 h-24 bg-slate-900 rounded-lg overflow-hidden border border-slate-300 flex-shrink-0 flex items-center justify-center relative group">
                  <el-image
                    v-if="form.endFrameImageUrl"
                    :src="form.endFrameImageUrl"
                    :preview-src-list="[form.endFrameImageUrl]"
                    preview-teleported
                    fit="cover"
                    class="w-full h-full cursor-pointer"
                  />
                  <span v-else class="text-2xl text-slate-500 select-none">🌆</span>

                  <div v-if="form.endFrameImageUrl" class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center text-white text-[10px] pointer-events-none">
                    🔍 放大预览
                  </div>
                </div>

                <div class="flex-1 flex flex-col justify-center gap-2">
                  <!-- AI 生成图片与上传按钮等长 -->
                  <el-button
                    size="small"
                    type="primary"
                    class="w-full !bg-purple-600 !border-purple-600 hover:!bg-purple-500"
                    @click="handleOpenAiGenerateEndFrame"
                  >
                    ✨ AI 生成图片
                  </el-button>
                  <el-upload
                    class="w-full [&_.el-upload]:w-full"
                    :show-file-list="false"
                    :before-upload="handleUploadEndFrame"
                    accept="image/*"
                  >
                    <el-button size="small" plain class="w-full" :loading="uploadingEnd">
                      📤 上传尾帧
                    </el-button>
                  </el-upload>
                  <div v-if="form.endFrameImageUrl" class="flex justify-end">
                    <el-button
                      size="small"
                      type="danger"
                      link
                      class="!p-0 text-xs"
                      @click="handleClearEndFrame"
                    >
                      🗑️ 清除尾帧
                    </el-button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </el-form>
    </div>
  </FullScreenPage>

  <!-- 每个提示词任务保持独立弹窗实例，避免切换分镜时共享草稿与流状态。 -->
  <ShotPromptDeriveModal
    v-for="panel in promptPanelSessions"
    :key="panel.key"
    :ref="instance => setPromptPanelRef(panel.key, instance)"
    @apply="result => handleApplyDerivedPromptsForPanel(panel.shotId, result)"
    @task-attached="taskId => handlePromptPanelTaskAttached(panel.key, taskId)"
    @task-starting="handlePromptPanelTaskStarting(panel.key)"
    @task-finished="handlePromptPanelTaskFinished(panel.key)"
    @task-restoration-finished="handlePromptPanelRestorationFinished(panel.key)"
    @panel-close="handlePromptPanelClosed(panel.key)"
  />

  <!-- 完整 Prompt 实时全景预览弹窗 -->
  <ShotPromptPreviewModal ref="promptPreviewModalRef" @success="handlePreviewModalSuccess" />

  <ShotVideoHistoryDrawer
    v-if="form.id"
    v-model="historyDrawerVisible"
    :shot-id="form.id"
    :shot-no="form.shotNo"
    :shot-name="form.shotName"
    @selected="handleHistorySelected"
    @deleted="handleHistoryDeleted"
  />

  <!-- 首尾帧专属渲染工坊弹窗 -->
  <ShotFirstEndFrameRenderModal ref="firstEndFrameRenderModalRef" @success="handleModalSuccess" />

  <!-- 多模态参考图与音频专属渲染工坊弹窗 -->
  <ShotMultiModalRefRenderModal ref="multiModalRefRenderModalRef" @success="handleModalSuccess" />

  <!-- AI 分镜生图与模型调度弹窗 -->
  <ShotImageGenerateModal
    ref="imageGenerateModalRef"
    @applied="handleImageGeneratedApplied"
  />

  <!-- 资产选取子弹窗 -->
  <el-dialog
    v-model="drawerAssetPickerVisible"
    :title="`选取参考资产 - ${drawerAssetPickerTitle}`"
    width="600px"
    append-to-body
    destroy-on-close
  >
    <div class="max-h-96 overflow-y-auto space-y-2 p-1">
      <div v-if="drawerAssetPickerList.length === 0" class="text-center py-8 text-xs text-gray-400">
        暂无可用资产素材
      </div>
      <div class="grid grid-cols-3 gap-3">
        <div
          v-for="(item, pIdx) in drawerAssetPickerList"
          :key="pIdx"
          class="border border-slate-200 hover:border-sky-500 rounded-lg p-2 cursor-pointer transition-all bg-white hover:shadow flex flex-col items-center gap-1.5 group relative"
          @click="handleConfirmSelectDrawerAsset(item)"
        >
          <div class="w-full h-24 bg-slate-100 rounded overflow-hidden flex items-center justify-center relative">
            <el-image :src="item.imageUrl" fit="cover" class="w-full h-full" />
            <span
              v-if="item.tag"
              class="absolute bottom-1 right-1 px-1.5 py-0.5 rounded text-[10px] font-medium bg-black/70 text-white"
            >
              {{ item.tag }}
            </span>
          </div>
          <span class="text-xs font-medium text-gray-800 truncate w-full text-center" :title="item.name">
            {{ item.name }}
          </span>
        </div>
      </div>
    </div>
  </el-dialog>

  <!-- 角色专属配音选取子弹窗 (直接从人物库引入已设计的母音配音) -->
  <el-dialog
    v-model="characterVoicePickerVisible"
    title="从人物库引入角色专属配音"
    width="560px"
    append-to-body
    destroy-on-close
  >
    <div class="max-h-96 overflow-y-auto space-y-2 p-1">
      <div v-if="charactersWithVoice.length === 0" class="text-center py-8 text-xs text-gray-400 space-y-2">
        <div class="text-2xl">🎙️</div>
        <div class="font-medium text-gray-600">当前人物库暂无可用的角色专属母音</div>
        <div class="text-[11px] text-gray-400 max-w-sm mx-auto">
          请前往「资产管理 -> 角色管理」点击“AI 声音设计”生成专属母音或上传本地音频，即可在此直接引入使用。
        </div>
      </div>
      <div v-else class="space-y-2">
        <div
          v-for="char in charactersWithVoice"
          :key="char.id"
          class="border border-slate-200 hover:border-sky-500 rounded-xl p-3 transition-all bg-white hover:shadow-xs flex items-center justify-between gap-3 group"
        >
          <div class="flex items-center gap-3 min-w-0 flex-1">
            <el-avatar :size="40" :src="char.avatarUrl" class="bg-sky-50 border border-sky-200 shrink-0">
              {{ char.name?.substring(0, 1) || '角' }}
            </el-avatar>
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-2">
                <span class="text-xs font-bold text-gray-800 truncate">{{ char.name }}</span>
                <el-tag size="small" type="info" class="!text-[10px] !px-1.5">{{ char.roleType || '角色' }}</el-tag>
              </div>
              <div class="text-[11px] text-gray-500 truncate mt-0.5" :title="char.voiceSampleText || char.voiceDesc || '专属声音母音'">
                {{ char.voiceSampleText || char.voiceDesc || '专属声音母音' }}
              </div>
            </div>
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <el-button
              size="small"
              circle
              :type="previewingVoiceUrl === char.voiceSampleUrl ? 'danger' : 'default'"
              @click="togglePreviewCharacterVoice(char.voiceSampleUrl!)"
            >
              {{ previewingVoiceUrl === char.voiceSampleUrl ? '⏸' : '▶' }}
            </el-button>
            <el-button
              size="small"
              type="primary"
              @click="handleConfirmSelectCharacterVoice(char)"
            >
              引入配音
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import FullScreenPage from '@/components/common/FullScreenPage.vue'
import StatusPill from '@/components/status/StatusPill.vue'
import DictSelect from '@/components/DictSelect.vue'
import { shotApi, shotGroupApi, episodeApi, dramaApi } from '@/api/drama'
import { voiceApi } from '@/api/voice'
import { aiProviderApi } from '@/api/ai-provider'
import { characterApi } from '@/api/res-character'
import { sceneApi } from '@/api/res-scene'
import { resPropApi, type ResPropItem } from '@/api/res-prop'
import { assetApi } from '@/api/res-asset'
import type { DramaShot, DramaShotGroup, CharacterShotRefInfo, PropShotRefInfo, ShotRefImage, ShotRefAudio, DirectorPlan } from '@/types/drama'
import type { ResCharacterOption, ResSceneOption } from '@/types/resource'
import type { AiProviderVO, AiModel } from '@/types/ai-provider'
import ShotPromptDeriveModal from './ShotPromptDeriveModal.vue'
import ShotPromptPreviewModal from './ShotPromptPreviewModal.vue'
import ShotFirstEndFrameRenderModal from './ShotFirstEndFrameRenderModal.vue'
import ShotMultiModalRefRenderModal from './ShotMultiModalRefRenderModal.vue'
import ShotImageGenerateModal from './ShotImageGenerateModal.vue'
import ShotVideoHistoryDrawer from './ShotVideoHistoryDrawer.vue'

const emit = defineEmits<{
  (e: 'success', shotId?: string | number): void
  (e: 'deleted', shotId?: string | number): void
}>()

type ShotAssetType = 'character' | 'scene' | 'prop'
type DerivedPromptResult = {
  prompt: string
  firstFramePrompt?: string
  endFramePrompt?: string
  videoPrompt?: string
  negativePrompt?: string
  characterRefs: CharacterShotRefInfo[]
  propRefs: PropShotRefInfo[]
  resSceneId?: string | number
  refImages: ShotRefImage[]
  refAudios: ShotRefAudio[]
  directorPlan?: DirectorPlan
  directorPlanJson?: string
}

const visible = ref(false)
const router = useRouter()
const isEdit = ref(false)
type PromptPanelSession = {
  key: string
  shotId: string
  taskId?: string
  running: boolean
  restoring: boolean
}
const promptPanelSessions = ref<PromptPanelSession[]>([])
const promptPanelRefs = ref<Record<string, any>>({})
let promptPanelSequence = 0
const promptPreviewModalRef = ref()
const firstEndFrameRenderModalRef = ref()
const multiModalRefRenderModalRef = ref()
const imageGenerateModalRef = ref()
const historyDrawerVisible = ref(false)

const loading = ref(false)
const saving = ref(false)
const rendering = ref(false)
const formRef = ref<FormInstance>()

const uploadingFirst = ref(false)
const uploadingEnd = ref(false)
const uploadingRefImg = ref(false)
const uploadingRefAud = ref(false)

const characterOptions = ref<ResCharacterOption[]>([])
const sceneOptions = ref<ResSceneOption[]>([])
const propOptions = ref<ResPropItem[]>([])
const groupOptions = ref<DramaShotGroup[]>([])
const characterRefs = ref<CharacterShotRefInfo[]>([])
const propRefs = ref<PropShotRefInfo[]>([])

const refImages = ref<ShotRefImage[]>([])
const refAudios = ref<ShotRefAudio[]>([])
const inheritingTail = ref(false)
const currentDramaAspectRatio = ref<string>('')

const form = reactive<DramaShot>({
  id: undefined,
  dramaId: 0,
  episodeId: 0,
  sceneId: 0,
  shotGroupId: undefined,
  shotNo: 1,
  shotName: '',
  shotType: undefined,
  cameraMovement: undefined,
  shotTypeLocked: false,
  cameraMovementLocked: false,
  duration: 3.0,
  scriptContent: '',
  actionDescription: '',
  dialogue: '',
  dialogueSpeaker: '',
  voiceover: '',
  soundEffect: '',
  resSceneId: undefined,
  customScenePrompt: '',
  generationMode: 'FIRST_LAST_FRAME',
  firstFramePrompt: '',
  endFramePrompt: '',
  endFrameImageUrl: '',
  startState: undefined,
  endState: undefined,
  prompt: '',
  videoPrompt: '',
  negativePrompt: '',
  stylePreset: '',
  previewImageUrl: '',
  videoUrl: '',
  audioUrl: '',
  renderStatus: 'INIT',
  latestTaskId: '',
  comfyWorkflowTemplateId: 'SDXL_TXT2IMG',
  directorPlanJson: undefined,
  sortOrder: 1
})

const parsedDirectorPlan = computed<DirectorPlan | null>(() => {
  if (!form.directorPlanJson) return null
  try {
    return JSON.parse(form.directorPlanJson) as DirectorPlan
  } catch {
    return null
  }
})

const rules: FormRules = {
  shotNo: [{ required: true, message: '请输入镜头序号', trigger: 'blur' }],
  duration: [
    { required: true, message: '请输入预估时长', trigger: 'blur' },
    {
      validator: (_rule: any, value: any, callback: any) => {
        if (value === undefined || value === null || value === '') {
          callback(new Error('请输入预估时长'))
        } else if (Number(value) <= 0) {
          callback(new Error('镜头时长必须大于 0 秒'))
        } else if (Number(value) > 15) {
          callback(new Error('镜头时长最长不得超过 15 秒'))
        } else {
          callback()
        }
      },
      trigger: ['blur', 'change']
    }
  ]
}

const totalAudioDuration = computed(() => {
  return refAudios.value.reduce((acc, curr) => acc + (Number(curr.duration) || 0), 0)
})

function getStatusLabel(status?: string) {
  switch (status) {
    case 'SUCCESS': return '渲染完成'
    case 'RENDERING': return '渲染中'
    case 'QUEUED': return '队列中'
    case 'FAILED': return '渲染失败'
    default: return '未渲染'
  }
}

async function loadOptions(dramaId: string | number, sceneId?: string | number) {
  try {
    const promises: Promise<any>[] = [
      characterApi.getOptions(dramaId),
      sceneApi.getOptions(dramaId),
      resPropApi.getOptions(dramaId)
    ]
    if (sceneId) {
      promises.push(shotGroupApi.getListBySceneId(sceneId))
    }
    const [cRes, sRes, pRes, gRes] = await Promise.all(promises)
    characterOptions.value = cRes || []
    sceneOptions.value = sRes || []
    propOptions.value = pRes || []
    if (gRes) {
      groupOptions.value = gRes || []
    }
  } catch (e) {
    characterOptions.value = []
    sceneOptions.value = []
    propOptions.value = []
    groupOptions.value = []
  }
}

async function handleApplyPrevTail() {
  if (!form.id) {
    return ElMessage.warning('请先保存分镜基础信息，再引用上一镜尾帧')
  }
  inheritingTail.value = true
  try {
    const res = await shotApi.inheritPreviousVideoTail(form.id)
    form.previewImageUrl = res.tailFrameUrl
    form.firstFrameSourceType = 'PREVIOUS_VIDEO_TAIL'
    form.firstFrameSourceShotId = res.sourceShotId
    form.firstFrameSourceVideoUrl = res.sourceVideoUrl
    if (res.reused) {
      ElMessage.success(`已引用缓存的上一镜 (S#${res.sourceShotNo || ''}) 视频尾帧！`)
    } else {
      ElMessage.success(`已从上一镜 (S#${res.sourceShotNo || ''}) 视频提取尾帧并设为当前首帧！`)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '提取/引用上一镜尾帧失败')
  } finally {
    inheritingTail.value = false
  }
}

// ✨ AI 生成首帧图 (打开生图弹窗：选择模型、调整提示词)
function handleOpenAiGenerateFirstFrame() {
  if (!form.id) {
    return ElMessage.warning('请先保存分镜基础信息，再进行 AI 生图')
  }
  const promptText = form.firstFramePrompt || form.prompt || form.scriptContent || form.actionDescription || ''
  imageGenerateModalRef.value?.open({
    shotId: form.id,
    frameType: 'FIRST_FRAME',
    shotTitle: `S${form.shotNo || ''} ${form.shotName || ''}`,
    prompt: promptText,
    negativePrompt: form.negativePrompt || '',
    slotOriginUrl: form.previewImageUrl || '',
    dramaId: form.dramaId,
    aspectRatio: currentDramaAspectRatio.value
  })
}

function handleClearFirstFrame() {
  form.previewImageUrl = ''
  form.firstFrameSourceType = undefined
  form.firstFrameSourceShotId = undefined
  form.firstFrameSourceVideoUrl = undefined
  if (form.id) {
    shotApi.update({ id: form.id, previewImageUrl: '' }).catch(() => {})
  }
  ElMessage.info('已清除首帧图')
}

async function handleUploadFirstFrame(file: File) {
  uploadingFirst.value = true
  try {
    const res = await assetApi.upload(file, 'shots')
    if (res && res.url) {
      form.previewImageUrl = res.url
      form.firstFrameSourceType = 'MANUAL_UPLOAD'
      form.firstFrameSourceShotId = undefined
      form.firstFrameSourceVideoUrl = undefined
      if (form.id) {
        await shotApi.setFirstFrame(form.id, res.url)
      }
      ElMessage.success('首帧图上传成功')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '上传首帧失败')
  } finally {
    uploadingFirst.value = false
  }
  return false
}

// ✨ AI 生成尾帧图 (打开生图弹窗：选择模型、调整提示词)
function handleOpenAiGenerateEndFrame() {
  if (!form.id) {
    return ElMessage.warning('请先保存分镜基础信息，再进行 AI 生图')
  }
  const promptText = form.endFramePrompt || form.prompt || form.scriptContent || form.actionDescription || ''
  imageGenerateModalRef.value?.open({
    shotId: form.id,
    frameType: 'END_FRAME',
    shotTitle: `S${form.shotNo || ''} ${form.shotName || ''}`,
    prompt: promptText,
    negativePrompt: form.negativePrompt || '',
    slotOriginUrl: form.endFrameImageUrl || '',
    firstFrameUrl: form.previewImageUrl || '',
    dramaId: form.dramaId,
    aspectRatio: currentDramaAspectRatio.value
  })
}

function handleImageGeneratedApplied(payload: { frameType: 'FIRST_FRAME' | 'END_FRAME'; url: string }) {
  if (payload.frameType === 'END_FRAME') {
    form.endFrameImageUrl = payload.url
    if (form.id) {
      shotApi.setEndFrame(form.id, payload.url).catch(() => {})
      emit('success', form.id)
    }
  } else {
    form.previewImageUrl = payload.url
    if (form.id) {
      shotApi.setFirstFrame(form.id, payload.url).catch(() => {})
      emit('success', form.id)
    }
  }
}

function handleClearEndFrame() {
  form.endFrameImageUrl = ''
  if (form.id) {
    shotApi.update({ id: form.id, endFrameImageUrl: '' }).catch(() => {})
  }
  ElMessage.info('已清除尾帧图')
}

async function handleUploadEndFrame(file: File) {
  uploadingEnd.value = true
  try {
    const res = await assetApi.upload(file, 'shots')
    if (res && res.url) {
      form.endFrameImageUrl = res.url
      ElMessage.success('尾帧图上传成功')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '上传尾帧失败')
  } finally {
    uploadingEnd.value = false
  }
  return false
}

// 资产选取器 (用于 REFERENCE_MODE)
const drawerAssetPickerVisible = ref(false)
const drawerAssetPickerTitle = ref('')
const drawerAssetPickerList = ref<Array<{ name: string; imageUrl: string; sourceType: 'SCENE' | 'CHARACTER_REFERENCE' | 'CHARACTER' | 'PROP'; sourceId: string | number; characterId?: string | number; lookId?: string | number; referenceRole?: string; tag?: string }>>([])

async function handleSelectAssetCommand(type: 'scene' | 'character' | 'prop') {
  if (refImages.value.length >= 9) {
    ElMessage.warning('参考图最多支持 9 张')
    return
  }
  drawerAssetPickerList.value = []
  if (type === 'scene') {
    drawerAssetPickerTitle.value = '场景资产'
    try {
      const list = await sceneApi.getOptions(form.dramaId)
      const items: any[] = []
      for (const s of list || []) {
        if (s.referenceImageUrl) {
          items.push({ name: `${s.name} (空间参考图)`, imageUrl: s.referenceImageUrl, sourceType: 'SCENE', sourceId: s.id, tag: '空间参考' })
        }
        if (s.coverUrl && s.coverUrl !== s.referenceImageUrl) {
          items.push({ name: `${s.name} (封面图)`, imageUrl: s.coverUrl, sourceType: 'SCENE', sourceId: s.id, tag: '场景图' })
        }
      }
      drawerAssetPickerList.value = items
    } catch (e) {}
  } else if (type === 'character') {
    drawerAssetPickerTitle.value = '人物造型资产'
    try {
      const list = await characterApi.getOptions(form.dramaId)
      const items: any[] = []
      for (const ch of list || []) {
        // 1. 人物设定参考图
        const charImg = ch.referenceImageUrl || ch.avatarUrl
        if (charImg) {
          items.push({
            name: `${ch.name} (身份设定图)`,
            imageUrl: charImg,
            sourceType: 'CHARACTER',
            sourceId: ch.id,
            characterId: ch.id,
            referenceRole: 'IDENTITY',
            tag: '人物设定'
          })
        }
        // 4. 多造型服装预览图
        for (const out of ch.outfits || []) {
          const outfitImg = out.referenceImageUrl
          if (outfitImg) {
            items.push({
              name: `${ch.name} - ${out.lookName}`,
              imageUrl: outfitImg,
              sourceType: 'CHARACTER_REFERENCE',
              sourceId: out.id,
              characterId: ch.id,
              lookId: out.id,
              referenceRole: 'COMBINED',
              tag: '造型服装'
            })
          }
        }
      }
      drawerAssetPickerList.value = items
    } catch (e) {}
  } else if (type === 'prop') {
    drawerAssetPickerTitle.value = '道具资产'
    try {
      const list = await resPropApi.getOptions(form.dramaId)
      drawerAssetPickerList.value = (list || [])
        .filter(p => p.coverUrl)
        .map(p => ({ name: p.name, imageUrl: p.coverUrl!, sourceType: 'PROP', sourceId: p.id, tag: '核心道具' }))
    } catch (e) {}
  }
  drawerAssetPickerVisible.value = true
}

function handleConfirmSelectDrawerAsset(item: any) {
  if (refImages.value.length >= 9) {
    ElMessage.warning('参考图最多支持 9 张')
    drawerAssetPickerVisible.value = false
    return
  }
  let defaultRole = 'SUBJECT'
  if (item.sourceType === 'SCENE') defaultRole = 'SCENE'
  else if (item.sourceType === 'PROP') defaultRole = 'PROP'

  refImages.value.push({
    id: `img_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
    name: item.name,
    imageUrl: item.imageUrl,
    sourceType: item.sourceType,
    sourceId: item.sourceId,
    characterId: item.characterId,
    lookId: item.lookId,
    referenceRole: item.referenceRole,
    usageRole: defaultRole
  })
  drawerAssetPickerVisible.value = false
  ElMessage.success(`已添加参考图: ${item.name}`)
}

async function handleUploadRefImage(file: File) {
  if (refImages.value.length >= 9) {
    ElMessage.warning('参考图最多支持 9 张')
    return false
  }
  uploadingRefImg.value = true
  try {
    const res = await assetApi.upload(file, 'shots')
    if (res && res.url) {
      refImages.value.push({
        id: `img_${Date.now()}`,
        name: file.name,
        imageUrl: res.url,
        sourceType: 'UPLOAD',
        usageRole: 'SUBJECT'
      })
      ElMessage.success('参考图上传成功！')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '上传参考图失败')
  } finally {
    uploadingRefImg.value = false
  }
  return false
}

const characterVoicePickerVisible = ref(false)
const previewingVoiceUrl = ref<string | null>(null)
let previewAudio: HTMLAudioElement | null = null

const charactersWithVoice = computed(() => {
  return (characterOptions.value || []).filter(c => !!c.voiceSampleUrl)
})

function handleOpenCharacterVoicePicker() {
  if (refAudios.value.length >= 3) {
    ElMessage.warning('参考音频最多支持 3 段')
    return
  }
  characterVoicePickerVisible.value = true
}

function togglePreviewCharacterVoice(url: string) {
  if (previewingVoiceUrl.value === url && previewAudio) {
    previewAudio.pause()
    previewingVoiceUrl.value = null
    return
  }
  if (previewAudio) {
    previewAudio.pause()
  }
  previewAudio = new Audio(url)
  previewingVoiceUrl.value = url
  previewAudio.onended = () => {
    previewingVoiceUrl.value = null
  }
  previewAudio.onerror = () => {
    previewingVoiceUrl.value = null
    ElMessage.error('音频加载失败')
  }
  previewAudio.play()
}

function handleConfirmSelectCharacterVoice(char: ResCharacterOption) {
  if (!char.voiceSampleUrl) return
  if (previewAudio) {
    previewAudio.pause()
    previewingVoiceUrl.value = null
  }
  const testAudio = new Audio(char.voiceSampleUrl)
  testAudio.addEventListener('loadedmetadata', () => {
    const dur = Math.round(testAudio.duration * 10) / 10
    refAudios.value.push({
      id: `aud_${Date.now()}`,
      sourceType: 'CHARACTER',
      characterId: char.id,
      characterName: char.name,
      name: `${char.name} (角色母音)`,
      text: char.voiceSampleText || char.voiceDesc || '',
      audioUrl: char.voiceSampleUrl || '',
      duration: dur,
      usageMode: 'VOICE_TIMBRE',
      language: 'zh'
    })
    characterVoicePickerVisible.value = false
    ElMessage.success(`已引入 ${char.name} 角色母音配音 (${dur}s)`)
  })
  testAudio.addEventListener('error', () => {
    refAudios.value.push({
      id: `aud_${Date.now()}`,
      sourceType: 'CHARACTER',
      characterId: char.id,
      characterName: char.name,
      name: `${char.name} (角色母音)`,
      text: char.voiceSampleText || char.voiceDesc || '',
      audioUrl: char.voiceSampleUrl || '',
      duration: 3.5,
      usageMode: 'VOICE_TIMBRE',
      language: 'zh'
    })
    characterVoicePickerVisible.value = false
    ElMessage.success(`已引入 ${char.name} 角色母音配音`)
  })
}

async function handleUploadRefAudio(file: File) {
  if (refAudios.value.length >= 3) {
    ElMessage.warning('参考音频最多支持 3 段')
    return false
  }
  const objectUrl = URL.createObjectURL(file)
  const audio = new Audio(objectUrl)
  audio.addEventListener('loadedmetadata', async () => {
    const dur = Math.round(audio.duration * 10) / 10
    if (dur < 2.0 || dur > 15.0) {
      ElMessage.warning(`单段音频官方规范时长为 2~15 秒，当前音频为 ${dur} 秒`)
    }
    uploadingRefAud.value = true
    try {
      const res = await assetApi.upload(file, 'shots')
      if (res && res.url) {
        refAudios.value.push({
          id: `aud_${Date.now()}`,
          sourceType: 'UPLOAD',
          name: file.name,
          audioUrl: res.url,
          duration: dur,
          usageMode: 'VOICE_TIMBRE',
          language: 'zh'
        })
        ElMessage.success(`音频上传成功 (${dur}s)`)
      }
    } catch (e: any) {
      ElMessage.error(e.message || '上传音频失败')
    } finally {
      uploadingRefAud.value = false
      URL.revokeObjectURL(objectUrl)
    }
  })
  audio.addEventListener('error', () => {
    ElMessage.error('无法读取所选音频的时长，请检查文件格式')
    URL.revokeObjectURL(objectUrl)
  })
  return false
}

function getOutfitsForCharacter(characterId?: string | number) {
  if (!characterId) return []
  const found = characterOptions.value.find(c => String(c.id) === String(characterId))
  return found?.outfits || []
}

function handleAddCharacter() {
  characterRefs.value.push({
    characterId: undefined,
    lookId: undefined,
    actionPrompt: '',
    emotionPrompt: '',
    positionTag: 'center'
  })
}

function handleRemoveCharacter(idx: number) {
  characterRefs.value.splice(idx, 1)
}

function handleCharacterChange(item: CharacterShotRefInfo, characterId: string | number) {
  item.characterId = characterId
  const char = characterOptions.value.find(c => String(c.id) === String(characterId))
  item.characterName = char?.name
  item.avatarUrl = char?.avatarUrl
  item.roleType = char?.roleType
  const defaultOutfit = char?.outfits?.find(o => o.isDefault === 1)
  if (defaultOutfit) {
    item.lookId = String(defaultOutfit.id)
    item.outfitName = defaultOutfit.lookName
    item.outfitPreviewUrl = defaultOutfit.referenceImageUrl
    item.designDesc = defaultOutfit.designDesc
    item.outfitPrompt = defaultOutfit.outfitPrompt
    item.appearancePrompt = defaultOutfit.appearancePrompt
  } else {
    item.lookId = undefined
    item.outfitName = undefined
    item.outfitPreviewUrl = undefined
    item.designDesc = undefined
    item.outfitPrompt = undefined
    item.appearancePrompt = undefined
  }
}

function handleOutfitChange(item: CharacterShotRefInfo, lookId?: string | number) {
  item.lookId = lookId
  const char = characterOptions.value.find(c => String(c.id) === String(item.characterId))
  const outfit = char?.outfits?.find(o => String(o.id) === String(lookId))
  item.outfitName = outfit?.lookName
  item.outfitPreviewUrl = outfit?.referenceImageUrl
  item.designDesc = outfit?.designDesc
  item.outfitPrompt = outfit?.outfitPrompt
  item.appearancePrompt = outfit?.appearancePrompt
}

function handleAddProp() {
  propRefs.value.push({
    propId: '',
    propName: '',
    propType: 'KEY_PROP',
    propPrompt: ''
  })
}

function handleRemoveProp(idx: number) {
  propRefs.value.splice(idx, 1)
}

function handlePropChange(item: PropShotRefInfo, propId: string | number) {
  const p = propOptions.value.find(x => String(x.id) === String(propId))
  if (p) {
    item.propId = String(p.id)
    item.propName = p.name
    item.propType = p.propType || 'KEY_PROP'
    item.propPrompt = p.propPrompt
    item.coverUrl = p.coverUrl
  }
}

async function bindAsset(assetType: ShotAssetType, assetId: string | number): Promise<boolean> {
  const normalizedId = String(assetId)
  if (assetType === 'scene') {
    form.resSceneId = normalizedId
  } else if (assetType === 'character') {
    const existing = characterRefs.value.find(item => String(item.characterId || '') === normalizedId)
    const item = existing || characterRefs.value.find(item => !item.characterId || String(item.characterId) === '0') || {
      characterId: normalizedId,
      lookId: undefined,
      actionPrompt: '',
      emotionPrompt: '',
      positionTag: 'center'
    }
    if (!existing && !characterRefs.value.includes(item)) {
      characterRefs.value.push(item)
    }
    handleCharacterChange(item, normalizedId)
  } else {
    const existing = propRefs.value.find(item => String(item.propId || '') === normalizedId)
    const item = existing || propRefs.value.find(item => !item.propId) || {
      propId: normalizedId,
      propName: '',
      propType: 'KEY_PROP',
      propPrompt: ''
    }
    if (!existing && !propRefs.value.includes(item)) {
      propRefs.value.push(item)
    }
    handlePropChange(item, normalizedId)
  }

  const saved = await handleSave(false)
  if (saved) {
    ElMessage.success('新资产已绑定到当前分镜')
  }
  return saved
}

function openCreate(dramaId: string | number, episodeId: string | number, sceneId: string | number, nextShotNo = 1, defaultResSceneId?: string | number, defaultGroupId?: string | number, dramaAspectRatio?: string) {
  isEdit.value = false
  characterRefs.value = []
  propRefs.value = []
  refImages.value = []
  refAudios.value = []
  currentDramaAspectRatio.value = dramaAspectRatio || ''
  if (!currentDramaAspectRatio.value && dramaId) {
    dramaApi.getById(dramaId).then((res: any) => {
      if (res?.aspectRatio) currentDramaAspectRatio.value = res.aspectRatio
    }).catch(() => {})
  }
  loadOptions(dramaId, sceneId)
  loadVoiceProviders()
  Object.assign(form, {
    id: undefined,
    dramaId,
    episodeId,
    sceneId,
    shotGroupId: defaultGroupId,
    shotNo: nextShotNo,
    shotName: `S01-${String(nextShotNo).padStart(2, '0')}`,
    shotType: undefined,
    cameraMovement: undefined,
    shotTypeLocked: false,
    cameraMovementLocked: false,
    duration: 3.0,
    scriptContent: '',
    actionDescription: '',
    dialogue: '',
    dialogueSpeaker: '',
    voiceover: '',
    soundEffect: '',
    resSceneId: defaultResSceneId,
    customScenePrompt: '',
    generationMode: 'FIRST_LAST_FRAME',
    firstFramePrompt: '',
    endFramePrompt: '',
    endFrameImageUrl: '',
    startState: undefined,
    endState: undefined,
    prompt: '',
    videoPrompt: '',
    negativePrompt: '',
    stylePreset: '',
    directorPlanJson: undefined,
    previewImageUrl: '',
    videoUrl: '',
    audioUrl: '',
    renderStatus: 'INIT',
    latestTaskId: '',
    comfyWorkflowTemplateId: 'SDXL_TXT2IMG',
    sortOrder: nextShotNo
  })
  visible.value = true
}

function copyToClipboard(text?: string, successMsg = '已复制到剪贴板') {
  if (!text) {
    ElMessage.warning('内容为空')
    return
  }
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success(successMsg)
  }).catch(() => {
    ElMessage.error('复制失败，请手动复制')
  })
}

async function openEdit(id: string | number, dramaId: string | number, dramaAspectRatio?: string) {
  isEdit.value = true
  visible.value = true
  loading.value = true
  // 切换分镜时先清除上一个分镜的计划，避免详情请求尚未返回时串显。
  form.directorPlanJson = undefined
  currentDramaAspectRatio.value = dramaAspectRatio || ''
  if (!currentDramaAspectRatio.value && dramaId) {
    dramaApi.getById(dramaId).then((res: any) => {
      if (res?.aspectRatio) currentDramaAspectRatio.value = res.aspectRatio
    }).catch(() => {})
  }
  try {
    const res = await shotApi.getById(id)
    if (res) {
      await loadOptions(dramaId, res.sceneId)
      loadVoiceProviders()
      Object.assign(form, res)
      // 兼容服务端省略 null 字段的序列化配置，始终以本次详情结果为准。
      form.directorPlanJson = res.directorPlanJson || undefined
      if (!form.generationMode) form.generationMode = 'FIRST_LAST_FRAME'
      characterRefs.value = res.characterRefs ? [...res.characterRefs] : []
      propRefs.value = res.propRefs ? [...res.propRefs] : []
      refImages.value = res.refImages ? [...res.refImages] : []
      refAudios.value = res.refAudios ? [...res.refAudios] : []
    }
  } catch (e: any) {
    ElMessage.error(e.message || '加载分镜详情失败')
  } finally {
    loading.value = false
  }
}

async function handleRender() {
  const saved = await handleSave(false)
  if (!saved && !form.id) return
  visible.value = false
  if (form.generationMode === 'REFERENCE_MODE') {
    multiModalRefRenderModalRef.value?.open({
      ...form,
      refImages: refImages.value,
      refAudios: refAudios.value
    }, currentDramaAspectRatio.value)
  } else {
    firstEndFrameRenderModalRef.value?.open(form, undefined, currentDramaAspectRatio.value)
  }
}

async function handleSave(closeAfter = false): Promise<boolean> {
  if (!formRef.value) return false
  if (characterRefs.value.some(item => !item.characterId || String(item.characterId) === '0')) {
    ElMessage.warning('请先为所有角色卡选择人物，或移除空白角色卡')
    return false
  }
  if (propRefs.value.some(item => !item.propId || String(item.propId) === '0')) {
    ElMessage.warning('请先为所有道具卡选择道具，或移除空白道具卡')
    return false
  }
  try {
    const valid = await formRef.value.validate()
    if (!valid) return false
  } catch {
    return false
  }
  saving.value = true
  try {
    // 渲染状态与视频产物由渲染/Take 流程维护，不能随旧表单一起回写。
    const {
      renderStatus: _renderStatus,
      latestTaskId: _latestTaskId,
      videoUrl: _videoUrl,
      currentVideoTakeId: _currentVideoTakeId,
      ...editableForm
    } = form
    const payload = {
      ...editableForm,
      shotType: form.shotType || undefined,
      cameraMovement: form.cameraMovement || undefined,
      characterRefs: characterRefs.value,
      propRefs: propRefs.value,
      refImages: refImages.value,
      refAudios: refAudios.value
    }
    if (isEdit.value && form.id) {
      await shotApi.update(payload)
      ElMessage.success('分镜保存成功')
      if (closeAfter) visible.value = false
      emit('success', form.id)
      return true
    } else {
      const res = await shotApi.create(payload)
      ElMessage.success('分镜创建成功')
      if (res) {
        form.id = res
        isEdit.value = true
      }
      if (closeAfter) visible.value = false
      emit('success', form.id)
      return true
    }
  } catch (e: any) {
    ElMessage.error(e.message || '保存分镜失败')
    return false
  } finally {
    saving.value = false
  }
}

function openVideoProcessing(name: 'VideoUpscale' | 'FrameInterpolation') {
  if (!form.id || !form.videoUrl) return
  visible.value = false
  router.push({ name, query: { shotId: String(form.id) } })
}

async function handleSaveAndCreateAsset(assetType: ShotAssetType) {
  if (saving.value) return
  const saved = await handleSave(false)
  if (!saved || !form.id) return

  const query: Record<string, string> = {
    returnTo: 'shot',
    shotId: String(form.id),
    assetType
  }
  const contextIds: Array<[string, string | number | undefined]> = [
    ['dramaId', form.dramaId],
    ['episodeId', form.episodeId],
    ['sceneId', form.sceneId]
  ]
  for (const [key, value] of contextIds) {
    if (value !== undefined && value !== null && value !== '') {
      query[key] = String(value)
    }
  }

  visible.value = false
  try {
    await router.push({ name: 'Assets', query })
  } catch (e: any) {
    visible.value = true
    ElMessage.error(e.message || '打开资产管理页面失败')
  }
}

function handleDelete() {
  if (!form.id) return
  ElMessageBox.confirm('确定要删除此分镜镜头吗？', '删除确认', {
    type: 'warning',
    confirmButtonText: '确定删除',
    cancelButtonText: '取消'
  }).then(async () => {
    try {
      await shotApi.delete(form.id!)
      ElMessage.success('分镜删除成功')
      visible.value = false
      emit('deleted', form.id)
    } catch (e: any) {
      ElMessage.error(e.message || '删除失败')
    }
  }).catch(() => {})
}

function handleOpenPromptPreview() {
  if (!form.id) {
    ElMessage.warning('请先保存分镜后再预览完整 Prompt')
    return
  }
  promptPreviewModalRef.value?.open(
    form.id,
    form.shotNo,
    form.shotName,
    form.shotTypeLocked ? form.shotType : '',
    form.cameraMovementLocked ? form.cameraMovement : ''
  )
}

async function handleOpenPromptDerive(eventOrTaskId?: string | Event) {
  // 模板中的 @click="handleOpenPromptDerive" 会自动传入 PointerEvent，不能把它当成任务 ID。
  const taskId = typeof eventOrTaskId === 'string' ? eventOrTaskId : undefined
  const shotSnapshot = { ...form }
  const shotId = String(shotSnapshot.id ?? '')
  const characterRefsSnapshot = characterRefs.value.map(item => ({ ...item }))
  const propRefsSnapshot = propRefs.value.map(item => ({ ...item }))
  const refImagesSnapshot = refImages.value.map(item => ({ ...item }))
  const refAudiosSnapshot = refAudios.value.map(item => ({ ...item }))
  const characterOptionsSnapshot = [...characterOptions.value]
  const sceneOptionsSnapshot = [...sceneOptions.value]
  const propOptionsSnapshot = [...propOptions.value]
  let episodeSummary = ''
  let dramaTitle = ''
  let stylePreset = shotSnapshot.stylePreset || ''
  let styleTone = ''
  let sceneName = ''

  if (shotSnapshot.resSceneId) {
    const foundScene = sceneOptionsSnapshot.find(s => String(s.id) === String(shotSnapshot.resSceneId))
    if (foundScene) sceneName = foundScene.name
  }

  try {
    if (shotSnapshot.episodeId) {
      const ep = await episodeApi.getById(shotSnapshot.episodeId)
      if (ep?.summary) episodeSummary = ep.summary
    }
    if (shotSnapshot.dramaId) {
      const dr = await dramaApi.getById(shotSnapshot.dramaId)
      if (dr) {
        dramaTitle = dr.title
        if (!stylePreset && dr.stylePreset) stylePreset = dr.stylePreset
        if (dr.styleTone) styleTone = dr.styleTone
      }
    }
  } catch (e) {
    console.warn('获取剧集或短剧背景信息失败', e)
  }

  const panelParams = {
    shot: shotSnapshot,
    characterRefs: characterRefsSnapshot,
    propRefs: propRefsSnapshot,
    resSceneId: shotSnapshot.resSceneId,
    sceneName,
    episodeSummary,
    dramaTitle,
    stylePreset,
    styleTone,
    generationMode: shotSnapshot.generationMode,
    refImages: refImagesSnapshot,
    refAudios: refAudiosSnapshot,
    characterOptions: characterOptionsSnapshot,
    sceneOptions: sceneOptionsSnapshot,
    propOptions: propOptionsSnapshot,
    taskId
  }
  await openPromptPanel(shotId, taskId, panelParams)
}

async function openPromptPanel(shotId: string, taskId: string | undefined, params: Record<string, any>) {
  let panel = taskId ? promptPanelSessions.value.find(item => item.taskId === taskId) : undefined

  if (!panel) {
    panel = {
      key: `prompt-${shotId || 'unsaved'}-${Date.now()}-${++promptPanelSequence}`,
      shotId,
      taskId,
      running: false,
      restoring: Boolean(shotId && taskId)
    }
    promptPanelSessions.value.push(panel)
  }

  await nextTick()
  await promptPanelRefs.value[panel.key]?.open(params)
}

function setPromptPanelRef(panelKey: string, instance: any) {
  if (instance) promptPanelRefs.value[panelKey] = instance
  else delete promptPanelRefs.value[panelKey]
}

function handlePromptPanelTaskAttached(panelKey: string, taskId: string) {
  const panel = promptPanelSessions.value.find(item => item.key === panelKey)
  if (!panel) return
  panel.taskId = String(taskId)
  panel.running = true
  panel.restoring = false
}

function handlePromptPanelTaskStarting(panelKey: string) {
  const panel = promptPanelSessions.value.find(item => item.key === panelKey)
  if (panel) panel.running = true
}

function handlePromptPanelTaskFinished(panelKey: string) {
  const panel = promptPanelSessions.value.find(item => item.key === panelKey)
  if (panel) panel.running = false
}

function handlePromptPanelRestorationFinished(panelKey: string) {
  const panel = promptPanelSessions.value.find(item => item.key === panelKey)
  if (panel) panel.restoring = false
}

function handlePromptPanelClosed(panelKey: string) {
  delete promptPanelRefs.value[panelKey]
  promptPanelSessions.value = promptPanelSessions.value.filter(item => item.key !== panelKey)
}

function isExplicitCameraChoice(value?: string | null) {
  return Boolean(value && value.trim() && value.toUpperCase() !== 'AUTO')
}

/** 从任务中心恢复指定的提示词分析任务，避免误打开同一镜头的其他历史任务。 */
async function openPromptTask(taskId: string) {
  if (!taskId || !form.id) return
  await handleOpenPromptDerive(String(taskId))
}

function handleApplyDerivedPrompts(res: DerivedPromptResult) {
  if (res.prompt) form.prompt = res.prompt
  if (res.firstFramePrompt !== undefined) form.firstFramePrompt = res.firstFramePrompt
  if (res.endFramePrompt !== undefined) form.endFramePrompt = res.endFramePrompt
  if (res.videoPrompt) form.videoPrompt = res.videoPrompt
  if (res.negativePrompt) form.negativePrompt = res.negativePrompt

  if (res.characterRefs) {
    characterRefs.value = res.characterRefs.map(item => ({ ...item }))
  }
  if (res.resSceneId !== undefined) {
    form.resSceneId = res.resSceneId
  }
  if (res.propRefs) {
    propRefs.value = res.propRefs.map(item => ({ ...item }))
  }
  if (res.refImages) {
    refImages.value = res.refImages.map(item => ({ ...item }))
  }
  if (res.refAudios) {
    refAudios.value = res.refAudios.map(item => ({ ...item }))
  }
  form.directorPlanJson = res.directorPlanJson || (res.directorPlan ? JSON.stringify(res.directorPlan) : undefined)
}

async function handleApplyDerivedPromptsForPanel(shotId: string, res: DerivedPromptResult) {
  if (String(form.id ?? '') === shotId) {
    handleApplyDerivedPrompts(res)
    return
  }
  if (!shotId) {
    ElMessage.warning('该分镜尚未保存，请重新打开原分镜后采纳提示词')
    return
  }

  const payload: Partial<DramaShot> = {
    id: shotId,
    prompt: res.prompt || undefined,
    firstFramePrompt: res.firstFramePrompt,
    endFramePrompt: res.endFramePrompt,
    videoPrompt: res.videoPrompt,
    negativePrompt: res.negativePrompt,
    characterRefs: res.characterRefs.map(item => ({ ...item })),
    propRefs: res.propRefs.map(item => ({ ...item })),
    resSceneId: res.resSceneId,
    refImages: res.refImages.map(item => ({ ...item })),
    refAudios: res.refAudios.map(item => ({ ...item })),
    directorPlanJson: res.directorPlanJson || (res.directorPlan ? JSON.stringify(res.directorPlan) : undefined)
  }
  try {
    await shotApi.update(payload)
    ElMessage.success('提示词及素材编排已回填并保存到对应分镜')
    emit('success', shotId)
  } catch (error: any) {
    ElMessage.error(error?.message || '保存对应分镜的 AI 分析结果失败')
  }
}

async function handlePreviewModalSuccess(shotId: string | number) {
  if (form.id === shotId && form.dramaId) {
    await openEdit(shotId, form.dramaId)
  }
  emit('success', shotId)
}

async function handleHistorySelected(payload: { takeId: string; videoUrl: string }) {
  form.videoUrl = payload.videoUrl
  form.currentVideoTakeId = payload.takeId
  form.lastFrameUrl = ''
  form.lastFrameSourceVideoUrl = ''
  form.lastFrameSourceTakeId = undefined
  if (form.id && form.dramaId) {
    await openEdit(form.id, form.dramaId)
  }
  if (form.id) emit('success', form.id)
}

async function handleHistoryDeleted() {
  if (form.id && form.dramaId) {
    await openEdit(form.id, form.dramaId)
    emit('success', form.id)
  }
}

async function handleModalSuccess(shotId: string | number) {
  visible.value = false
  emit('success', shotId)
}

// 🎙️ 分镜台词声音克隆操作
const generatingDrawerVoice = ref(false)
const isPlayingDrawerAudio = ref(false)
let drawerAudio: HTMLAudioElement | null = null

const voiceProviders = ref<AiProviderVO[]>([])
const voiceModels = ref<AiModel[]>([])
const voiceProviderId = ref<number | string>()
const voiceModelCode = ref<string>('mimo-v2.5-tts-voiceclone')
const voiceEmotion = ref<string>('')

async function loadVoiceProviders() {
  try {
    const list = await aiProviderApi.getListEnabled()
    voiceProviders.value = list || []
    if (voiceProviderId.value) {
      const exists = voiceProviders.value.some(p => String(p.id) === String(voiceProviderId.value))
      if (!exists) voiceProviderId.value = undefined
    }
    // 智能优选：若未选，优先选中支持语音/TTS 的提供商（如 Xiaomi），避免误选到纯生图的本地网关
    if (!voiceProviderId.value && voiceProviders.value.length > 0) {
      const ttsPreferred = voiceProviders.value.find(p =>
        /xiaomi|mimo|tts|voice|audio|语音/i.test(p.providerName || '') ||
        /xiaomi|mimo|tts|voice|audio|语音/i.test(p.providerCode || '')
      )
      voiceProviderId.value = ttsPreferred ? String(ttsPreferred.id) : String(voiceProviders.value[0].id)
    }
    if (voiceProviderId.value) {
      await handleVoiceProviderChange(voiceProviderId.value)
    }
  } catch (err) {
    console.error('加载配音提供商失败', err)
  }
}

async function handleVoiceProviderChange(pid?: number | string) {
  voiceModels.value = []
  if (!pid) return
  try {
    const list = await aiProviderApi.getModelList(pid, 'TTS,AUDIO,VOICE')
    let validModels = (list || []).filter(m => m.status === 1)
    if (validModels.length === 0) {
      const allList = await aiProviderApi.getModelList(pid)
      validModels = (allList || []).filter(m => m.status === 1)
    }
    voiceModels.value = validModels
    // 优先匹配 mimo-v2.5-tts-voiceclone，或者保留已有有效选择
    const targetModel = voiceModels.value.find(m => m.modelCode === 'mimo-v2.5-tts-voiceclone')
      || voiceModels.value.find(m => m.modelCode === voiceModelCode.value)
      || voiceModels.value[0]
    if (targetModel) {
      voiceModelCode.value = targetModel.modelCode
    }
  } catch (e) {
    console.error('加载配音模型列表失败:', e)
  }
}

async function handleGenerateDrawerVoice() {
  if (!form.id) {
    return ElMessage.warning('请先保存分镜基础信息，再生成配音')
  }
  if (!form.dialogue) {
    return ElMessage.warning('请先输入分镜台词对白')
  }
  try {
    generatingDrawerVoice.value = true
    const res = await voiceApi.generateShotVoice(form.id, {
      customDialogue: form.dialogue,
      providerId: voiceProviderId.value,
      modelCode: voiceModelCode.value,
      emotion: voiceEmotion.value
    })
    form.audioUrl = res.audioUrl
    if (res.duration) {
      const audioSec = Math.ceil(res.duration * 10) / 10
      if (!form.duration || form.duration < audioSec) {
        form.duration = audioSec
      }
    }
    ElMessage.success('分镜配音克隆生成成功！')
    emit('success', form.id)
  } catch (error: any) {
    ElMessage.error(error?.message || '配音生成失败')
  } finally {
    generatingDrawerVoice.value = false
  }
}

function togglePlayDrawerAudio() {
  if (!form.audioUrl) return
  if (isPlayingDrawerAudio.value && drawerAudio) {
    drawerAudio.pause()
    isPlayingDrawerAudio.value = false
    return
  }
  if (drawerAudio) {
    drawerAudio.pause()
  }
  drawerAudio = new Audio(form.audioUrl)
  isPlayingDrawerAudio.value = true
  drawerAudio.onended = () => {
    isPlayingDrawerAudio.value = false
  }
  drawerAudio.onerror = () => {
    isPlayingDrawerAudio.value = false
    ElMessage.error('音频播放失败')
  }
  drawerAudio.play()
}

function handleClearDrawerAudio() {
  if (drawerAudio) {
    drawerAudio.pause()
    drawerAudio = null
  }
  isPlayingDrawerAudio.value = false
  form.audioUrl = ''
}

function close() {
  visible.value = false
}

defineExpose({
  openCreate,
  openEdit,
  openPromptTask,
  bindAsset,
  close,
  visible
})
</script>
