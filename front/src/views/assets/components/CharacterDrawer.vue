<template>
  <div v-if="visible" class="space-y-4">
    <!-- 主内容区顶部导航与操作栏 -->
    <el-card shadow="never" class="!border-gray-200">
      <div class="flex items-center justify-between flex-wrap gap-3">
        <div class="flex items-center gap-3">
          <el-button @click="handleClose()">
            <el-icon><ArrowLeft /></el-icon>
            返回列表
          </el-button>
          <div class="h-4 w-px bg-slate-200"></div>
          <div class="flex items-center gap-2">
            <span class="text-xs text-slate-400">角色与场景资产 / 角色资产管理 /</span>
            <h2 class="text-base font-bold text-slate-800 tracking-tight">
              {{ isEdit ? `编辑人物角色 - ${formData.name || ''}` : '新建人物角色' }}
            </h2>
            <el-tag v-if="formData.identityStatus === 'PARTIAL'" size="small" type="warning" effect="plain">
              临时称谓
            </el-tag>
            <el-tag v-else-if="formData.identityStatus === 'UNRESOLVED'" size="small" type="danger" effect="plain">
              待消歧
            </el-tag>
            <el-tag v-else-if="formData.identityStatus === 'MERGED'" size="small" type="info" effect="plain">
              已合并
            </el-tag>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <el-button @click="handleClose()">取消</el-button>
          <el-button type="primary" :loading="saving" @click="handleSubmit">
            保存人物
          </el-button>
        </div>
      </div>
    </el-card>

    <div class="bg-white p-6 rounded-xl border border-slate-200 shadow-2xs">
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="120px"
        label-position="right"
        class="pr-2"
      >
        <el-tabs v-model="activeTab" class="mb-4">
          <!-- 标签页 1: 基础资料 -->
          <el-tab-pane label="基础资料" name="basic">
            <div class="grid grid-cols-3 gap-2">
              <el-form-item label="人物姓名" prop="name" class="col-span-2">
                <el-input
                  v-model="formData.name"
                  placeholder="如：林婉清 / Sarah Connor"
                  maxlength="100"
                  show-word-limit
                />
              </el-form-item>

              <el-form-item label="身份认证" prop="identityStatus" label-width="80px">
                <el-select v-model="formData.identityStatus" placeholder="身份状态">
                  <el-option label="已确认姓名" value="CONFIRMED" />
                  <el-option label="临时称谓" value="PARTIAL" />
                  <el-option label="待消歧" value="UNRESOLVED" />
                </el-select>
              </el-form-item>
            </div>

            <div class="grid grid-cols-2 gap-2">
              <el-form-item label="性别" prop="gender">
                <el-select v-model="formData.gender" placeholder="选择性别">
                  <el-option label="男 (Male)" value="MALE" />
                  <el-option label="女 (Female)" value="FEMALE" />
                  <el-option label="其他 (Other)" value="OTHER" />
                  <el-option label="未知 (Unknown)" value="UNKNOWN" />
                </el-select>
              </el-form-item>

              <el-form-item label="年龄段" prop="ageGroup">
                <el-select v-model="formData.ageGroup" placeholder="选择年龄段" clearable>
                  <el-option label="少年 (Teenager)" value="TEENAGER" />
                  <el-option label="青年 (Youth)" value="YOUTH" />
                  <el-option label="中年 (Middle-aged)" value="MIDDLE_AGED" />
                  <el-option label="老年 (Elderly)" value="ELDERLY" />
                </el-select>
              </el-form-item>
            </div>

            <div class="grid grid-cols-2 gap-2">
              <el-form-item label="角色定位" prop="roleType">
                <el-select v-model="formData.roleType" placeholder="角色定位">
                  <el-option label="主角 (Protagonist)" value="PROTAGONIST" />
                  <el-option label="反派 (Antagonist)" value="ANTAGONIST" />
                  <el-option label="配角 (Supporting)" value="SUPPORTING" />
                  <el-option label="路人/群演 (Extra)" value="EXTRA" />
                </el-select>
              </el-form-item>

              <el-form-item label="所属库" prop="dramaId">
                <el-select v-model="formData.dramaId" placeholder="所属资源库" filterable class="w-full">
                  <el-option label="🌐 全局公共资源库" value="0" />
                  <el-option
                    v-for="d in dramaOptions"
                    :key="String(d.id)"
                    :label="`🎬 ${d.title}`"
                    :value="String(d.id)"
                  />
                </el-select>
              </el-form-item>
            </div>

            <el-form-item label="中文外貌描述 (原著视觉 SSOT)" prop="appearanceDesc">
              <el-input
                v-model="formData.appearanceDesc"
                type="textarea"
                :rows="3"
                placeholder="如：28岁商界女强人，皮肤极白，冷艳丹凤眼，黑色及腰大波浪长发，右眼角细小泪痣..."
              />
              <div class="text-xs text-gray-400 mt-1">原著中文外貌真实源，作为所有多模态英文提示词生成与镜头动作对齐的基准</div>
            </el-form-item>

            <!-- 更多人设与扩展设置折叠面板 -->
            <el-collapse v-model="activeCollapseNames" class="more-collapse !border-none mt-2">
              <el-collapse-item name="more" class="!border-none">
                <template #title>
                  <div class="flex items-center gap-2 text-xs font-semibold text-slate-700 w-full pr-2">
                    <el-icon class="text-indigo-500"><Operation /></el-icon>
                    <span>更多设置 (别名、专属音色、身份证据链与状态)</span>
                    <el-tag v-if="characterAliases.length > 0" size="small" type="info" class="!text-[10px] !h-4 !px-1.5">
                      {{ characterAliases.length }} 个别名
                    </el-tag>
                    <el-tag v-if="formData.voiceSampleUrl" size="small" type="success" effect="light" class="!text-[10px] !h-4 !px-1.5">
                      音色已配置
                    </el-tag>
                  </div>
                </template>

                <div class="pt-3">
                  <el-form-item label="性格与言行风格 (内在灵魂)" prop="personality">
                    <el-input
                      v-model="formData.personality"
                      type="textarea"
                      :rows="2"
                      placeholder="如：孤傲冷酷、心思缜密、言语极简尖锐（内在心智与对白语气，专供台词与TTS配音，禁止写外貌）"
                      maxlength="255"
                      show-word-limit
                    />
                  </el-form-item>

                  <!-- 🏷️ 角色别名与历史称谓 (Aliases) -->
                  <el-form-item label="角色别名/提及词">
                    <div class="w-full space-y-2">
                      <div class="flex items-center justify-between">
                        <div class="text-xs text-gray-500">
                          原著不同章节中的称谓（小名、尊称、代号、职务、外貌代称等），用于 AI 自动消歧映射
                        </div>
                        <el-button
                          v-if="formData.id"
                          type="primary"
                          link
                          size="small"
                          @click="handleOpenAliasModal"
                        >
                          管理全部别名 ({{ characterAliases.length }})
                        </el-button>
                      </div>

                      <div class="flex flex-wrap items-center gap-1.5 min-h-[32px] p-2 bg-slate-50/80 rounded-lg border border-slate-200">
                        <span v-if="characterAliases.length === 0" class="text-xs text-gray-400">
                          暂无配置别名
                        </span>
                        <el-tag
                          v-for="al in characterAliases"
                          :key="al.id"
                          size="small"
                          closable
                          :type="getAliasTagType(al.aliasType)"
                          effect="plain"
                          class="cursor-pointer select-none"
                          @close="handleQuickDeleteAlias(al)"
                          @click="handleEditAliasTag(al)"
                        >
                          <span class="font-bold mr-1">{{ al.alias }}</span>
                          <span class="text-[10px] opacity-70">[{{ getAliasTypeLabel(al.aliasType) }}]</span>
                        </el-tag>
                      </div>

                      <div v-if="formData.id" class="flex items-center gap-2 pt-1">
                        <el-input
                          v-model="quickAliasText"
                          placeholder="快速输入别名，如：清姐 / 董事长"
                          size="small"
                          class="!w-56"
                          @keyup.enter="handleQuickAddAlias"
                        />
                        <el-select v-model="quickAliasType" size="small" class="!w-28">
                          <el-option label="正式名" value="NAME" />
                          <el-option label="尊称" value="TITLE" />
                          <el-option label="昵称" value="NICKNAME" />
                          <el-option label="外貌代称" value="DESCRIPTION" />
                          <el-option label="职业身份" value="ROLE" />
                          <el-option label="代词指代" value="PRONOUN" />
                        </el-select>
                        <el-button
                          size="small"
                          type="primary"
                          plain
                          :loading="addingQuickAlias"
                          @click="handleQuickAddAlias"
                        >
                          + 快速添加
                        </el-button>
                      </div>
                    </div>
                  </el-form-item>

                  <!-- 身份证据链快捷查看 -->
                  <el-form-item label="身份证据链">
                    <button
                      type="button"
                      class="text-left p-3 rounded-lg border border-slate-200 bg-white hover:border-indigo-300 hover:bg-indigo-50/40 transition-colors cursor-pointer w-full"
                      :disabled="!formData.id"
                      @click="emit('open-evidences', formData as ResCharacter)"
                    >
                      <div class="text-xs font-semibold text-slate-700 flex items-center justify-between">
                        <span>📜 查看身份证据链</span>
                        <span v-if="formData.id" class="text-[10px] text-indigo-600 font-normal">进入查看 →</span>
                      </div>
                      <div class="text-[11px] text-slate-400 mt-1">查看角色消歧依据、原著提取文本与模型置信度</div>
                    </button>
                  </el-form-item>

                  <!-- 🎙️ 角色专属音色 -->
                  <el-form-item label="角色专属音色">
                    <div
                      v-if="formData.voiceSampleUrl"
                      class="w-full flex items-center justify-between p-3 rounded-lg border border-indigo-200 bg-indigo-50/40"
                    >
                      <div class="flex items-center gap-3">
                        <el-button
                          circle
                          size="small"
                          :type="isPlayingVoice ? 'danger' : 'primary'"
                          @click="togglePlayVoiceSample"
                        >
                          {{ isPlayingVoice ? '⏸' : '▶' }}
                        </el-button>
                        <div class="overflow-hidden">
                          <div class="text-xs font-semibold text-gray-800 flex items-center gap-1.5">
                            <span>专属母音已就绪</span>
                            <el-tag size="small" type="success" effect="light" class="!text-[10px] !h-5 !px-1.5">已配置</el-tag>
                            <span v-if="voiceDuration" class="text-[10px] text-indigo-600 font-mono">({{ voiceDuration }}s)</span>
                          </div>
                          <div class="text-[11px] text-gray-500 truncate max-w-[260px]" :title="formData.voiceDesc || formData.voiceSampleUrl">
                            {{ formData.voiceDesc || formData.voiceSampleUrl }}
                          </div>
                        </div>
                      </div>
                      <div class="flex items-center gap-1 shrink-0">
                        <el-button size="small" type="primary" plain @click="handleOpenVoiceDesignModal">
                          调整音色
                        </el-button>
                        <el-button size="small" text type="danger" @click="handleClearVoiceSample">
                          清除
                        </el-button>
                      </div>
                    </div>

                    <div
                      v-else
                      class="w-full flex items-center justify-between p-3 rounded-lg border border-dashed border-gray-200 bg-gray-50/50 hover:border-indigo-200 transition-colors"
                    >
                      <div class="flex items-center gap-2">
                        <span class="text-base">🎙️</span>
                        <div>
                          <div class="text-xs font-medium text-gray-700">暂未配置专属声音母音</div>
                          <div class="text-[11px] text-gray-400">使用自然语言设计音色或上传音频，用于分镜台词克隆</div>
                        </div>
                      </div>
                      <el-button size="small" type="primary" plain @click="handleOpenVoiceDesignModal">
                        ✨ AI 声音设计
                      </el-button>
                    </div>
                  </el-form-item>

                  <div class="grid grid-cols-2 gap-2">
                    <el-form-item label="显示排序" prop="sortOrder">
                      <el-input-number v-model="formData.sortOrder" :min="0" :max="999" class="!w-full" />
                    </el-form-item>
                    <el-form-item label="状态" prop="status">
                      <el-radio-group v-model="formData.status">
                        <el-radio :value="1">启用</el-radio>
                        <el-radio :value="0">停用</el-radio>
                      </el-radio-group>
                    </el-form-item>
                  </div>

                  <el-form-item label="备注说明" prop="remark">
                    <el-input v-model="formData.remark" type="textarea" :rows="3" placeholder="内部备忘或参考" />
                  </el-form-item>
                </div>
              </el-collapse-item>
            </el-collapse>
          </el-tab-pane>

          <!-- 标签页 2: 稳定身份特征 (跨造型基准 Prompt) -->
          <el-tab-pane label="稳定身份特征" name="prompt">
            <div class="flex items-center justify-between bg-slate-50 p-3 rounded-lg border border-slate-200 mb-4">
              <div class="text-xs text-slate-600">
                💡 维护跨所有造型稳定的生物基准特征（骨相、五官、发质基底与身形体态）。严禁在此编写具体衣服或临时伤痕。
              </div>
              <el-button
                size="small"
                type="primary"
                plain
                class="font-medium hover:scale-[1.02] transition-transform"
                @click="handleDerivePrompts"
              >
                ⚡ AI生成提示词
              </el-button>
            </div>

            <div class="p-4 mb-4 rounded-lg border border-slate-200 bg-white shadow-xs space-y-3">
              <div class="flex items-center justify-between pb-1.5 border-b border-slate-100">
                <span class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                  <span>👤</span> 稳定身份外貌 Prompt (appearancePrompt)
                </span>
                <span class="text-[11px] text-slate-400">发型发色、五官骨相、身材体型等跨服装稳定特征</span>
              </div>
              <div>
                <el-input
                  v-model="formData.appearancePrompt"
                  type="textarea"
                  :rows="4"
                  placeholder="如: anime style, soft light lineart, handsome young swordsman, black hair tied in ponytail, sharp eyes, athletic lean build..."
                />
              </div>
              <div>
                <label class="text-[11px] font-semibold text-slate-500 block mb-1">身份级专属全局负向约束 (negativePrompt)</label>
                <el-input
                  v-model="formData.negativePrompt"
                  type="textarea"
                  :rows="2"
                  placeholder="如: photorealistic, thick black outline, bad anatomy, deformed, mutated hands, eyeglasses"
                />
              </div>
            </div>
          </el-tab-pane>

          <!-- 标签页 3: 人物设定参考图（当前默认造型） -->
          <el-tab-pane label="人物设定参考图（默认造型）" name="model">
            <div class="text-xs text-slate-600 mb-4 bg-slate-50 p-3 rounded-lg border border-slate-200 flex items-center justify-between">
              <span>💡 本系统已将模型统一为「人物 + 多个完整造型」。当前标签展示的内容即为该角色的<strong>当前默认造型</strong>，也是未指定造型分镜的渲染基准。</span>
              <el-button size="small" type="primary" link @click="activeTab = 'looks'">
                前往全部造型管理 →
              </el-button>
            </div>

            <div v-if="defaultLook" class="p-5 rounded-xl border border-indigo-200 bg-gradient-to-r from-indigo-50/40 via-purple-50/20 to-white shadow-xs space-y-4">
              <div class="flex items-center justify-between pb-3 border-b border-indigo-100">
                <div class="flex items-center gap-2">
                  <span class="text-base font-bold text-slate-800">当前默认造型：{{ defaultLook.lookName }}</span>
                  <el-tag :type="getLookTypeTag(defaultLook.lookType)" size="small" effect="plain">
                    {{ getLookTypeLabel(defaultLook.lookType) }}
                  </el-tag>
                  <el-tag :type="getImageStatusTagType(defaultLook.imageStatus)" size="small" effect="dark">
                    {{ getImageStatusLabel(defaultLook.imageStatus) }}
                  </el-tag>
                </div>
                <el-button size="small" type="primary" plain @click="handleEditLookInTab(defaultLook)">
                  编辑该造型参数
                </el-button>
              </div>

              <div class="flex items-start gap-6">
                <!-- 默认造型图片 -->
                <div class="relative group cursor-pointer shrink-0">
                  <div v-if="defaultLook.referenceImageUrl" class="relative">
                    <el-image
                      :src="defaultLook.referenceImageUrl"
                      :preview-src-list="[defaultLook.referenceImageUrl]"
                      preview-teleported
                      fit="cover"
                      class="w-32 h-32 rounded-xl border-2 border-indigo-300 block shadow-sm"
                    />
                    <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded-xl flex items-center justify-center text-white text-xs pointer-events-none">
                      🔍 预览高清图
                    </div>
                  </div>
                  <div
                    v-else
                    class="w-32 h-32 rounded-xl border-2 border-dashed border-slate-300 bg-slate-50 flex flex-col items-center justify-center text-slate-400 gap-1 text-xs"
                  >
                    <span class="text-2xl">👔</span>
                    <span>暂无参考图</span>
                  </div>
                </div>

                <!-- 默认造型图文详情 -->
                <div class="flex-1 space-y-2.5">
                  <div class="flex items-center gap-2">
                    <el-upload
                      action="#"
                      :show-file-list="false"
                      :http-request="(opt: UploadRequestOptions) => handleUploadLookReference(opt, defaultLook)"
                      accept="image/*"
                    >
                      <el-button size="small" type="primary" :loading="lookUploading">
                        {{ defaultLook.referenceImageUrl ? '重新上传默认造型图' : '上传参考图' }}
                      </el-button>
                    </el-upload>
                    <AssetImageGenerator
                      target-type="CHARACTER_OUTFIT"
                      :target-id="defaultLook.id"
                      image-slot="REFERENCE"
                      :prompt="defaultLook.outfitPrompt"
                      :negative-prompt="defaultLook.negativePrompt"
                      @applied="(url) => handleApplyLookImage(defaultLook, url)"
                    />
                    <el-button
                      v-if="defaultLook.referenceImageUrl"
                      size="small"
                      type="danger"
                      text
                      @click="handleClearLookImage(defaultLook)"
                    >
                      清除图片
                    </el-button>
                  </div>

                  <div v-if="defaultLook.imageStatus === 'STALE'" class="text-xs text-amber-600 bg-amber-50 p-2 rounded border border-amber-200">
                    ⚠️ <strong>图文状态已过期：</strong>提示词变更后尚未重新生成或上传图片，渲染器将发出提示但不会借用其他造型图。
                  </div>

                  <div class="bg-white/90 p-3 rounded-lg border border-slate-200 text-xs space-y-1.5 font-mono">
                    <div class="text-slate-500 font-sans font-medium text-[11px]">服装与概念摘要：</div>
                    <div class="text-slate-800 font-sans">{{ defaultLook.designDesc || '暂无中文设计描述' }}</div>
                    <div class="text-slate-500 font-sans font-medium text-[11px] pt-1">造型专属 Prompt:</div>
                    <div class="text-slate-700 break-words line-clamp-3">{{ defaultLook.outfitPrompt || '（未设置服装 Prompt）' }}</div>
                  </div>
                </div>
              </div>
            </div>

            <div v-else class="text-center py-12 bg-slate-50 rounded-xl border border-slate-200">
              <div class="text-slate-400 text-sm">该角色暂无默认造型，请前往「服装与造型」标签页添加或指定默认造型。</div>
              <el-button type="primary" class="mt-3" size="small" @click="activeTab = 'looks'">
                前往添加第一个造型
              </el-button>
            </div>
          </el-tab-pane>

          <!-- 标签页 4: 服装与造型（全部造型工作台：左侧列表 + 右侧表单） -->
          <el-tab-pane label="服装与造型 (全部造型)" name="looks">
            <div class="flex items-center justify-between bg-slate-50 p-3 rounded-lg border border-slate-200 mb-4">
              <div class="text-xs text-slate-600">
                💡 统一造型管理：同一人物可以拥有多套完整造型（日常服、战损、晚宴装等）。第一个造型自动成为默认；切换默认造型将直接同步封面与未指定造型的分镜。
              </div>
              <el-button type="primary" size="small" :icon="Plus" :disabled="!formData.id" @click="handleCreateLook">
                新增造型
              </el-button>
            </div>

            <div v-if="!formData.id" class="text-center py-10 bg-slate-50 rounded-xl border border-slate-200 text-xs text-slate-500">
              请先保存角色基础信息，即可开始配置角色多造型资产。
            </div>

            <div v-else class="grid grid-cols-1 lg:grid-cols-[380px_1fr] gap-4">
              <!-- 左侧：造型列表 -->
              <div class="space-y-2 max-h-[580px] overflow-y-auto pr-1">
                <div
                  v-for="look in lookList"
                  :key="String(look.id)"
                  class="p-3 rounded-xl border transition-all cursor-pointer relative"
                  :class="[
                    selectedLook?.id === look.id ? 'border-indigo-500 bg-indigo-50/30 shadow-xs' : 'border-slate-200 bg-white hover:border-indigo-200',
                    look.isDefault === 1 ? 'ring-1 ring-emerald-400' : ''
                  ]"
                  @click="selectedLook = look"
                >
                  <div class="flex items-start gap-3">
                    <div class="relative shrink-0 group" @click.stop="selectedLook = look">
                      <el-image
                        v-if="look.referenceImageUrl"
                        :src="look.referenceImageUrl"
                        :preview-src-list="[look.referenceImageUrl]"
                        preview-teleported
                        fit="cover"
                        class="w-14 h-14 rounded-lg border border-slate-200 block shadow-xs"
                      />
                      <div
                        v-if="look.referenceImageUrl"
                        class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded-lg flex items-center justify-center text-white text-[10px] pointer-events-none"
                      >
                        预览
                      </div>
                      <div v-else class="w-14 h-14 rounded-lg border border-dashed border-slate-200 bg-slate-50 flex items-center justify-center text-slate-400 text-base">
                        👔
                      </div>
                    </div>

                    <div class="flex-1 min-w-0">
                      <div class="flex items-center justify-between gap-1">
                        <div class="font-bold text-slate-800 text-xs truncate">{{ look.lookName }}</div>
                        <el-tag v-if="look.isDefault === 1" type="success" size="small" effect="dark" class="!text-[10px] !h-4 !px-1 shrink-0">
                          默认
                        </el-tag>
                      </div>
                      <div class="text-[11px] text-slate-400 truncate mt-0.5">
                        {{ look.designDesc || look.lookType || '无中文描述' }}
                      </div>
                      <div class="flex items-center gap-1.5 mt-1">
                        <el-tag :type="getLookTypeTag(look.lookType)" size="small" effect="plain" class="!text-[10px] !h-4 !px-1">
                          {{ getLookTypeLabel(look.lookType) }}
                        </el-tag>
                        <el-tag :type="getImageStatusTagType(look.imageStatus)" size="small" effect="light" class="!text-[10px] !h-4 !px-1">
                          {{ getImageStatusLabel(look.imageStatus) }}
                        </el-tag>
                        <span v-if="look.status === 0" class="text-[10px] text-red-500">已停用</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div v-if="lookList.length === 0" class="text-center py-8 text-xs text-slate-400">
                  暂无造型，点击上方“新增造型”
                </div>
              </div>

              <!-- 右侧：当前选中造型的编辑表单 -->
              <div class="bg-slate-50/50 p-4 rounded-xl border border-slate-200 min-h-[460px]">
                <div v-if="selectedLook" class="space-y-3">
                  <div class="flex items-center justify-between pb-2 border-b border-slate-200">
                    <div class="font-bold text-slate-800 text-sm flex items-center gap-2">
                      <span>{{ selectedLook.id ? `编辑造型 - ${selectedLook.lookName}` : '新增造型' }}</span>
                      <el-tag v-if="selectedLook.isDefault === 1" type="success" size="small">当前默认出镜造型</el-tag>
                    </div>
                    <div class="flex items-center gap-2">
                      <el-button
                        v-if="selectedLook.id && selectedLook.isDefault !== 1"
                        size="small"
                        type="success"
                        plain
                        @click="handleSetDefaultLook(selectedLook)"
                      >
                        设为默认
                      </el-button>
                      <el-popconfirm
                        v-if="selectedLook.id"
                        title="确定删除此造型吗？若该造型为默认，将自动将下一套可用造型提升为默认。"
                        @confirm="handleDeleteLook(selectedLook)"
                      >
                        <template #reference>
                          <el-button size="small" type="danger" link :disabled="lookList.length <= 1">
                            删除
                          </el-button>
                        </template>
                      </el-popconfirm>
                      <el-button size="small" type="primary" :loading="savingLook" @click="handleSaveSelectedLook">
                        保存造型
                      </el-button>
                    </div>
                  </div>

                  <el-form label-position="top" size="small" class="space-y-2">
                    <div class="grid grid-cols-2 gap-3">
                      <el-form-item label="造型名称" required>
                        <el-input v-model="selectedLook.lookName" placeholder="如：日常便服 / 战术作战装 / 晚礼服" />
                      </el-form-item>
                      <el-form-item label="视觉状态类型" required>
                        <el-select v-model="selectedLook.lookType" placeholder="选择类型" class="w-full">
                          <el-option label="基础日常 (BASE)" value="BASE" />
                          <el-option label="特殊服饰 (COSTUME)" value="COSTUME" />
                          <el-option label="年龄阶段 (AGE_PHASE)" value="AGE_PHASE" />
                          <el-option label="伪装易容 (DISGUISE)" value="DISGUISE" />
                          <el-option label="受损战损 (DAMAGE)" value="DAMAGE" />
                          <el-option label="自定义变体 (CUSTOM)" value="CUSTOM" />
                        </el-select>
                      </el-form-item>
                    </div>

                    <el-form-item label="造型视觉概念描述" required>
                      <el-input
                        v-model="selectedLook.designDesc"
                        type="textarea"
                        :rows="2"
                        placeholder="如：黑色及膝大衣，内搭深灰高领毛衣，银色锁骨链，战损撕裂痕迹..."
                      />
                    </el-form-item>

                    <div class="flex items-center justify-between pt-1">
                      <span class="text-xs font-semibold text-slate-700">服装专属 Prompt (outfitPrompt)</span>
                      <el-button size="small" type="primary" link @click="handleDeriveSelectedLookPrompt">
                        ⚡ AI 智能生成提示词
                      </el-button>
                    </div>
                    <el-input
                      v-model="selectedLook.outfitPrompt"
                      type="textarea"
                      :rows="3"
                      placeholder="如: wearing dark charcoal coat, high collar sweater, fine fabric texture..."
                    />

                    <el-form-item label="该造型特有妆发/状态 (appearancePrompt)">
                      <el-input
                        v-model="selectedLook.appearancePrompt"
                        type="textarea"
                        :rows="2"
                        placeholder="如妆发改动、伤痕、血迹、年龄变化（无变化则留空）"
                      />
                    </el-form-item>

                    <el-form-item label="造型专属负向约束 (negativePrompt)">
                      <el-input
                        v-model="selectedLook.negativePrompt"
                        type="textarea"
                        :rows="1"
                        placeholder="如: bright colors, casual clothes"
                      />
                    </el-form-item>

                    <!-- 造型唯一参考图配置 -->
                    <el-form-item label="该造型唯一参考图 (禁止跨造型借图)">
                      <div class="flex items-center gap-4 bg-white p-3 rounded-lg border border-slate-200 w-full">
                        <div class="relative shrink-0 group">
                          <el-image
                            v-if="selectedLook.referenceImageUrl"
                            :src="selectedLook.referenceImageUrl"
                            :preview-src-list="[selectedLook.referenceImageUrl]"
                            preview-teleported
                            fit="cover"
                            class="w-16 h-16 rounded-lg border border-slate-200 block shadow-xs cursor-pointer"
                          />
                          <div
                            v-if="selectedLook.referenceImageUrl"
                            class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded-lg flex items-center justify-center text-white text-[10px] pointer-events-none"
                          >
                            预览
                          </div>
                          <div v-else class="w-16 h-16 rounded-lg border border-dashed border-slate-200 bg-slate-50 flex items-center justify-center text-slate-400 text-xs">
                            未设置
                          </div>
                        </div>

                        <div class="flex-1 space-y-1.5">
                          <div class="flex items-center gap-2">
                            <el-upload
                              action="#"
                              :show-file-list="false"
                              :http-request="(opt: UploadRequestOptions) => handleUploadLookReference(opt, selectedLook)"
                              accept="image/*"
                            >
                              <el-button size="small" :loading="lookUploading">
                                {{ selectedLook.referenceImageUrl ? '重新上传' : '上传参考图' }}
                              </el-button>
                            </el-upload>
                            <AssetImageGenerator
                              target-type="CHARACTER_OUTFIT"
                              :target-id="selectedLook.id"
                              image-slot="REFERENCE"
                              :prompt="selectedLook.outfitPrompt"
                              :negative-prompt="selectedLook.negativePrompt"
                              @applied="(url) => handleApplyLookImage(selectedLook, url)"
                            />
                            <el-button
                              v-if="selectedLook.referenceImageUrl"
                              size="small"
                              type="danger"
                              text
                              @click="handleClearLookImage(selectedLook)"
                            >
                              清除
                            </el-button>
                          </div>
                          <div class="text-[11px] text-slate-400">
                            当前图片状态：<el-tag :type="getImageStatusTagType(selectedLook.imageStatus)" size="small">{{ getImageStatusLabel(selectedLook.imageStatus) }}</el-tag>
                          </div>
                        </div>
                      </div>
                    </el-form-item>

                    <div class="grid grid-cols-2 gap-3 pt-1">
                      <el-form-item label="状态">
                        <el-radio-group v-model="selectedLook.status">
                          <el-radio :value="1">启用</el-radio>
                          <el-radio :value="0">停用</el-radio>
                        </el-radio-group>
                      </el-form-item>
                      <el-form-item label="设为默认">
                        <el-switch
                          v-model="selectedLook.isDefault"
                          :active-value="1"
                          :inactive-value="0"
                          :disabled="selectedLook.isDefault === 1"
                          active-text="默认出镜造型"
                        />
                      </el-form-item>
                    </div>
                  </el-form>
                </div>

                <div v-else class="text-center py-20 text-slate-400 text-xs">
                  请在左侧选择一个造型进行编辑，或点击“新增造型”。
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-form>
    </div>
  </div>

  <!-- 依据人设生成稳定身份 Prompt 弹窗 -->
  <PromptDeriveModal
    v-for="panel in promptPanels"
    :key="panel.key"
    :ref="instance => setPromptPanelRef(panel.key, instance)"
    @apply="result => handleApplyDerivedPromptsForPanel(panel, result)"
    @task-starting="setPromptPanelRunning(panel.key, true)"
    @task-finished="setPromptPanelRunning(panel.key, false)"
    @panel-close="closePromptPanel(panel.key)"
  />

  <!-- 🎙️ AI 角色专属音色设计工坊弹窗 -->
  <VoiceDesignModal
    ref="voiceDesignModalRef"
    @apply="handleApplyVoiceDesign"
  />

  <!-- 🏷️ 角色别名管理工坊弹窗 -->
  <AliasModal
    ref="aliasModalRef"
    @updated="handleAliasModalUpdated"
  />

  <!-- 👗 AI 智能生成造型提示词专属弹窗 (双模式) -->
  <LookPromptDeriveModal
    v-for="panel in lookPromptPanels"
    :key="panel.key"
    :ref="instance => setLookPromptPanelRef(panel.key, instance)"
    @apply="result => handleApplyLookDerivedPromptsForPanel(panel, result)"
    @task-starting="setLookPromptPanelRunning(panel.key, true)"
    @task-finished="setLookPromptPanelRunning(panel.key, false)"
    @panel-close="closeLookPromptPanel(panel.key)"
  />

  <div v-if="promptPanels.length || lookPromptPanels.length" class="fixed right-5 bottom-5 z-[2050] flex max-w-[calc(100vw-2.5rem)] flex-col items-end gap-2">
    <el-button
      v-for="panel in promptPanels"
      :key="panel.key"
      size="small"
      :type="panel.running ? 'warning' : 'primary'"
      plain
      @click="reopenPromptPanel(panel.key)"
    >
      {{ panel.running ? '生成中' : '待采纳' }} · 身份提示词 · {{ panel.targetName || '未命名人物' }}
    </el-button>
    <el-button
      v-for="panel in lookPromptPanels"
      :key="panel.key"
      size="small"
      :type="panel.running ? 'warning' : 'primary'"
      plain
      @click="reopenLookPromptPanel(panel.key)"
    >
      {{ panel.running ? '生成中' : '待采纳' }} · 造型提示词 · {{ panel.targetName || '未命名造型' }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { ArrowLeft, Operation, Plus } from '@element-plus/icons-vue'
import { characterApi } from '@/api/res-character'
import { dramaApi } from '@/api/drama'
import { assetApi } from '@/api/res-asset'
import type { ResCharacter, ResCharacterLook, CharacterVisualPromptDeriveVO, OutfitPromptDeriveVO, ResCharacterAlias } from '@/types/resource'
import type { DramaOption } from '@/types/drama'
import AssetImageGenerator from './AssetImageGenerator.vue'
import PromptDeriveModal from './PromptDeriveModal.vue'
import LookPromptDeriveModal from './LookPromptDeriveModal.vue'
import VoiceDesignModal from './VoiceDesignModal.vue'
import AliasModal from './AliasModal.vue'

type PromptPanelSession = { key: string; targetId: string; targetName: string; formVersion: number; running: boolean }
type LookPromptPanelSession = PromptPanelSession & {
  characterId: string
  lookId: string
  lookRef: ResCharacterLook | null
}
const promptPanels = ref<PromptPanelSession[]>([])
const lookPromptPanels = ref<LookPromptPanelSession[]>([])
const promptPanelRefs = ref<Record<string, InstanceType<typeof PromptDeriveModal>>>({})
const lookPromptPanelRefs = ref<Record<string, InstanceType<typeof LookPromptDeriveModal>>>({})
let promptPanelSequence = 0
let formVersion = 0
const voiceDesignModalRef = ref<InstanceType<typeof VoiceDesignModal>>()
const aliasModalRef = ref<InstanceType<typeof AliasModal>>()

// 角色别名状态
const characterAliases = ref<ResCharacterAlias[]>([])
const quickAliasText = ref('')
const quickAliasType = ref('NAME')
const addingQuickAlias = ref(false)

// 造型状态
const lookList = ref<ResCharacterLook[]>([])
const selectedLook = ref<ResCharacterLook | null>(null)
const defaultLook = computed(() => lookList.value.find(l => l.isDefault === 1) || lookList.value[0] || null)
const savingLook = ref(false)
const lookUploading = ref(false)

const emit = defineEmits<{
  (e: 'success', characterId?: string | number): void
  (e: 'cancel'): void
  (e: 'visible-change', val: boolean): void
  (e: 'open-evidences', character: ResCharacter): void
}>()

const visible = ref(false)
watch(visible, (val) => {
  emit('visible-change', val)
})
const saving = ref(false)
const activeTab = ref('basic')
const activeCollapseNames = ref<string[]>([])
const formRef = ref<FormInstance>()
const dramaOptions = ref<DramaOption[]>([])

async function loadDramaOptions() {
  try {
    const res = await dramaApi.getOptions()
    dramaOptions.value = res || []
  } catch (err) {
    console.error('加载短剧列表失败', err)
  }
}

const formData = reactive<Partial<ResCharacter>>({
  id: undefined,
  dramaId: '0',
  name: '',
  canonicalName: '',
  displayName: '',
  identityStatus: 'CONFIRMED',
  gender: 'UNKNOWN',
  ageGroup: 'YOUTH',
  roleType: 'PROTAGONIST',
  personality: '',
  appearanceDesc: '',
  appearancePrompt: '',
  triggerWords: '',
  negativePrompt: '',
  loraName: '',
  loraWeight: 1.0,
  voiceId: '',
  voiceDesc: '',
  voiceSampleText: '',
  voiceSampleUrl: '',
  sortOrder: 0,
  status: 1,
  remark: ''
})

const isEdit = computed(() => !!formData.id)

// 专属母音试听状态
const isPlayingVoice = ref(false)
let currentAudio: HTMLAudioElement | null = null
const voiceDuration = ref<number | null>(null)

const formRules: FormRules = {
  name: [{ required: true, message: '请输入人物名称', trigger: 'blur' }],
  gender: [{ required: true, message: '请选择性别', trigger: 'change' }],
  roleType: [{ required: true, message: '请选择角色定位', trigger: 'change' }]
}

function handleDerivePrompts() {
  if (!formData.name && !formData.appearanceDesc && !formData.personality) {
    return ElMessage.warning('请先填写角色姓名、性格或中文外貌描述')
  }
  const panel: PromptPanelSession = {
    key: `character-prompt-${Date.now()}-${++promptPanelSequence}`,
    targetId: String(formData.id ?? ''),
    targetName: String(formData.name || formData.canonicalName || ''),
    formVersion,
    running: false
  }
  promptPanels.value.push(panel)
  void nextTick(() => promptPanelRefs.value[panel.key]?.open({ ...formData }))
}

function applyCharacterPrompts(res: CharacterVisualPromptDeriveVO) {
  if (res.appearancePrompt) formData.appearancePrompt = res.appearancePrompt
  if (res.negativePrompt) formData.negativePrompt = res.negativePrompt
}

async function handleApplyDerivedPromptsForPanel(panel: PromptPanelSession, res: CharacterVisualPromptDeriveVO) {
  if (panel.targetId && String(formData.id ?? '') === panel.targetId) {
    applyCharacterPrompts(res)
    return
  }
  if (!panel.targetId && panel.formVersion === formVersion) {
    applyCharacterPrompts(res)
    return
  }
  if (!panel.targetId) {
    ElMessage.warning('该人物尚未保存，提示词结果保留在任务面板中；请重新打开原人物后采纳')
    return
  }

  try {
    await characterApi.update({
      id: panel.targetId,
      appearancePrompt: res.appearancePrompt,
      negativePrompt: res.negativePrompt
    })
    emit('success', panel.targetId)
    ElMessage.success('身份提示词已保存到对应人物')
  } catch (error: any) {
    ElMessage.error(error?.message || '保存对应人物提示词失败')
  }
}

function setPromptPanelRef(key: string, instance: any) {
  if (instance) promptPanelRefs.value[key] = instance
  else delete promptPanelRefs.value[key]
}

function setPromptPanelRunning(key: string, running: boolean) {
  const panel = promptPanels.value.find(item => item.key === key)
  if (panel) panel.running = running
}

function reopenPromptPanel(key: string) {
  promptPanelRefs.value[key]?.reopen()
}

function closePromptPanel(key: string) {
  delete promptPanelRefs.value[key]
  promptPanels.value = promptPanels.value.filter(item => item.key !== key)
}

async function loadLooks() {
  if (!formData.id) {
    lookList.value = []
    selectedLook.value = null
    return
  }
  try {
    const res = await characterApi.getLookList(formData.id)
    lookList.value = res || []
    if (lookList.value.length > 0) {
      if (!selectedLook.value || !lookList.value.some(l => l.id === selectedLook.value?.id)) {
        selectedLook.value = lookList.value.find(l => l.isDefault === 1) || lookList.value[0]
      } else {
        selectedLook.value = lookList.value.find(l => l.id === selectedLook.value?.id) || lookList.value[0]
      }
    } else {
      selectedLook.value = null
    }
  } catch (e) {
    console.error('加载角色造型列表失败', e)
  }
}

function handleEditLookInTab(look: ResCharacterLook) {
  selectedLook.value = look
  activeTab.value = 'looks'
}

function handleCreateLook() {
  selectedLook.value = {
    id: undefined,
    characterId: formData.id,
    lookName: '',
    // 新增造型默认作为角色的基础视觉状态，特殊服饰由用户显式选择。
    lookType: 'BASE',
    designDesc: '',
    outfitPrompt: '',
    appearancePrompt: '',
    negativePrompt: '',
    referenceImageUrl: '',
    isDefault: lookList.value.length === 0 ? 1 : 0,
    status: 1,
    sortOrder: lookList.value.length
  }
}

async function handleSaveSelectedLook() {
  if (!selectedLook.value) return
  const look = selectedLook.value
  if (!look.lookName?.trim()) {
    return ElMessage.warning('请输入造型名称')
  }
  if (!look.designDesc?.trim()) {
    return ElMessage.warning('请输入造型视觉概念描述')
  }
  try {
    savingLook.value = true
    look.characterId = formData.id
    if (look.id) {
      await characterApi.updateLook(look.id, look)
      ElMessage.success('造型已更新')
    } else {
      const newId = await characterApi.createLook(formData.id!, look)
      ElMessage.success('造型已创建')
      look.id = newId
    }
    await loadLooks()
    emit('success')
  } catch (err: any) {
    ElMessage.error(err.message || '保存造型失败')
  } finally {
    savingLook.value = false
  }
}

async function handleSetDefaultLook(look: ResCharacterLook) {
  if (!look.id) return
  try {
    await characterApi.setDefaultLook(look.id)
    ElMessage.success(`已将【${look.lookName}】设为默认造型`)
    await loadLooks()
    emit('success')
  } catch (err: any) {
    ElMessage.error(err.message || '设置默认造型失败')
  }
}

async function handleDeleteLook(look: ResCharacterLook) {
  if (!look.id) return
  try {
    await characterApi.deleteLook(look.id)
    ElMessage.success('造型已删除')
    await loadLooks()
    emit('success')
  } catch (err: any) {
    ElMessage.error(err.message || '删除造型失败')
  }
}

async function handleUploadLookReference(options: UploadRequestOptions, targetLook: ResCharacterLook | null) {
  if (!targetLook) return
  try {
    lookUploading.value = true
    const res = await assetApi.upload(options.file, 'outfit')
    targetLook.referenceImageUrl = res.url
    targetLook.previewImageUrl = res.url
    targetLook.imageStatus = 'SYNCED'
    if (targetLook.id) {
      await characterApi.updateLook(targetLook.id, targetLook)
      await loadLooks()
      emit('success')
    }
    ElMessage.success('造型参考图上传成功')
  } catch (error) {
    ElMessage.error('上传图片失败')
  } finally {
    lookUploading.value = false
  }
}

async function handleApplyLookImage(targetLook: ResCharacterLook | null, url: string) {
  if (!targetLook) return
  targetLook.referenceImageUrl = url
  targetLook.previewImageUrl = url
  targetLook.imageStatus = 'SYNCED'
  if (targetLook.id) {
    await characterApi.updateLook(targetLook.id, targetLook)
    await loadLooks()
    emit('success')
  }
}

async function handleClearLookImage(targetLook: ResCharacterLook | null) {
  if (!targetLook) return
  targetLook.referenceImageUrl = ''
  targetLook.previewImageUrl = ''
  targetLook.imageStatus = 'MISSING'
  if (targetLook.id) {
    await characterApi.updateLook(targetLook.id, targetLook)
    await loadLooks()
    emit('success')
  }
}

function handleDeriveSelectedLookPrompt() {
  if (!selectedLook.value) return
  const look = selectedLook.value
  if (!look.designDesc?.trim()) {
    return ElMessage.warning('请先填写造型视觉概念描述')
  }
  const drama = dramaOptions.value.find(d => String(d.id) === String(formData.dramaId)) || null
  const panel: LookPromptPanelSession = {
    key: `character-look-prompt-${Date.now()}-${++promptPanelSequence}`,
    targetId: String(look.id ?? ''),
    targetName: String(look.lookName || ''),
    formVersion,
    running: false,
    characterId: String(formData.id ?? ''),
    lookId: String(look.id ?? ''),
    lookRef: look
  }
  lookPromptPanels.value.push(panel)
  void nextTick(() => lookPromptPanelRefs.value[panel.key]?.open({ ...look }, { ...formData }, drama as any))
}

function applyLookPrompts(target: ResCharacterLook, res: OutfitPromptDeriveVO) {
  if (res.outfitPrompt) {
    target.outfitPrompt = res.outfitPrompt
  }
  if (res.appearancePrompt) {
    target.appearancePrompt = res.appearancePrompt
  }
  if (res.negativePrompt) {
    target.negativePrompt = res.negativePrompt
  }
}

async function handleApplyLookDerivedPromptsForPanel(panel: LookPromptPanelSession, res: OutfitPromptDeriveVO) {
  const sameCharacter = String(formData.id ?? '') === panel.characterId
  const sameLook = sameCharacter && selectedLook.value && (
    (panel.lookId && String(selectedLook.value.id ?? '') === panel.lookId)
    || (!panel.lookId && selectedLook.value === panel.lookRef && panel.formVersion === formVersion)
  )
  if (sameLook && selectedLook.value) {
    applyLookPrompts(selectedLook.value, res)
    ElMessage.success('已采纳 AI 生成的造型提示词')
    return
  }
  if (panel.lookId) {
    try {
      await characterApi.updateLook(panel.lookId, {
        outfitPrompt: res.outfitPrompt,
        appearancePrompt: res.appearancePrompt,
        negativePrompt: res.negativePrompt
      })
      emit('success', panel.characterId || undefined)
      ElMessage.success('造型提示词已保存到对应造型')
    } catch (error: any) {
      ElMessage.error(error?.message || '保存对应造型提示词失败')
    }
    return
  }
  ElMessage.warning('该造型尚未保存，提示词结果保留在任务面板中；请重新打开原造型后采纳')
}

function setLookPromptPanelRef(key: string, instance: any) {
  if (instance) lookPromptPanelRefs.value[key] = instance
  else delete lookPromptPanelRefs.value[key]
}

function setLookPromptPanelRunning(key: string, running: boolean) {
  const panel = lookPromptPanels.value.find(item => item.key === key)
  if (panel) panel.running = running
}

function reopenLookPromptPanel(key: string) {
  lookPromptPanelRefs.value[key]?.reopen()
}

function closeLookPromptPanel(key: string) {
  delete lookPromptPanelRefs.value[key]
  lookPromptPanels.value = lookPromptPanels.value.filter(item => item.key !== key)
}

function open(character?: ResCharacter, presetDramaId?: string | number) {
  formVersion++
  activeTab.value = 'basic'
  loadDramaOptions()
  if (character) {
    const primaryName = character.canonicalName || character.name || character.displayName || ''
    const rawDramaId = character.dramaId
    const normalizedDramaId = (rawDramaId === undefined || rawDramaId === null || rawDramaId === 0 || rawDramaId === '0' || String(rawDramaId) === '0')
      ? '0'
      : String(rawDramaId)
    Object.assign(formData, {
      ...character,
      dramaId: normalizedDramaId,
      name: primaryName,
      canonicalName: character.canonicalName || primaryName,
      displayName: character.displayName || primaryName,
      identityStatus: character.identityStatus || 'CONFIRMED',
      loraWeight: character.loraWeight ?? 1.0
    })
  } else {
    Object.assign(formData, {
      id: undefined,
      dramaId: presetDramaId !== undefined && presetDramaId !== null && presetDramaId !== ''
        ? String(presetDramaId)
        : '0',
      name: '',
      canonicalName: '',
      displayName: '',
      identityStatus: 'CONFIRMED',
      gender: 'UNKNOWN',
      ageGroup: 'YOUTH',
      roleType: 'PROTAGONIST',
      personality: '',
      appearanceDesc: '',
      appearancePrompt: '',
      triggerWords: '',
      negativePrompt: '',
      loraName: '',
      loraWeight: 1.0,
      voiceId: '',
      voiceDesc: '',
      voiceSampleText: '',
      voiceSampleUrl: '',
      sortOrder: 0,
      status: 1,
      remark: ''
    })
  }
  if (currentAudio) {
    currentAudio.pause()
    currentAudio = null
  }
  isPlayingVoice.value = false
  voiceDuration.value = null
  quickAliasText.value = ''
  if (character?.id) {
    loadAliases()
    loadLooks()
  } else {
    characterAliases.value = []
    lookList.value = []
    selectedLook.value = null
  }
  activeCollapseNames.value = []
  visible.value = true
}

function handleOpenAliasModal() {
  if (formData.id) {
    aliasModalRef.value?.open(formData as ResCharacter)
  }
}

function handleEditAliasTag(al: ResCharacterAlias) {
  if (formData.id) {
    aliasModalRef.value?.open(formData as ResCharacter, al)
  }
}

function handleAliasModalUpdated() {
  loadAliases()
  emit('success')
}

async function loadAliases() {
  if (!formData.id) {
    characterAliases.value = []
    return
  }
  try {
    const res = await characterApi.getAliases(formData.id)
    characterAliases.value = res || []
    formData.aliases = res || []
  } catch (e) {
    console.error('加载角色别名失败', e)
  }
}

async function handleQuickAddAlias() {
  const text = quickAliasText.value.trim()
  if (!text) {
    return ElMessage.warning('请输入别名内容')
  }
  if (!formData.id) return
  try {
    addingQuickAlias.value = true
    await characterApi.addAlias({
      characterId: formData.id,
      dramaId: formData.dramaId,
      alias: text,
      aliasType: quickAliasType.value,
      confidence: 1.0,
      status: 1
    })
    ElMessage.success('已成功添加别名: ' + text)
    quickAliasText.value = ''
    await loadAliases()
    emit('success')
  } catch (error: any) {
    ElMessage.error(error.message || '添加别名失败')
  } finally {
    addingQuickAlias.value = false
  }
}

async function handleQuickDeleteAlias(al: ResCharacterAlias) {
  if (!al.id) return
  try {
    await characterApi.deleteAlias(al.id)
    ElMessage.success('已删除别名: ' + al.alias)
    await loadAliases()
    emit('success')
  } catch (error: any) {
    ElMessage.error(error.message || '删除别名失败')
  }
}

function getAliasTypeLabel(type?: string): string {
  const map: Record<string, string> = {
    NAME: '正式名',
    DESCRIPTION: '外貌',
    TITLE: '尊称',
    NICKNAME: '昵称',
    ROLE: '职业',
    PRONOUN: '代词',
    OTHER: '其他'
  }
  return type ? map[type] || type : '正式名'
}

function getAliasTagType(type?: string): 'success' | 'warning' | 'primary' | 'danger' | 'info' {
  const map: Record<string, 'success' | 'warning' | 'primary' | 'danger' | 'info'> = {
    NAME: 'success',
    DESCRIPTION: 'warning',
    TITLE: 'primary',
    NICKNAME: 'danger',
    ROLE: 'warning',
    PRONOUN: 'info',
    OTHER: 'info'
  }
  return type ? map[type] || 'info' : 'info'
}

function getLookTypeLabel(type?: string): string {
  const map: Record<string, string> = {
    BASE: '日常基准',
    COSTUME: '特殊服饰',
    AGE_PHASE: '年龄阶段',
    DISGUISE: '伪装易容',
    DAMAGE: '战损状态',
    CUSTOM: '自定义'
  }
  return type ? map[type] || type : '造型'
}

function getLookTypeTag(type?: string): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
    BASE: 'primary',
    COSTUME: 'success',
    AGE_PHASE: 'info',
    DISGUISE: 'warning',
    DAMAGE: 'danger',
    CUSTOM: 'info'
  }
  return type ? map[type] || 'info' : 'info'
}

function getImageStatusLabel(status?: string): string {
  const map: Record<string, string> = {
    MISSING: '缺少图片',
    SYNCED: '图文已同步',
    STALE: '提示词已更新(图过期)'
  }
  return status ? map[status] || status : '已就绪'
}

function getImageStatusTagType(status?: string): 'info' | 'success' | 'warning' {
  const map: Record<string, 'info' | 'success' | 'warning'> = {
    MISSING: 'info',
    SYNCED: 'success',
    STALE: 'warning'
  }
  return status ? map[status] || 'info' : 'success'
}

function handleOpenVoiceDesignModal() {
  voiceDesignModalRef.value?.open(formData)
}

function handleApplyVoiceDesign(data: {
  voiceDesc: string
  voiceSampleText: string
  voiceSampleUrl: string
  voiceId: string
  duration?: number | null
}) {
  formData.voiceDesc = data.voiceDesc
  formData.voiceSampleText = data.voiceSampleText
  formData.voiceSampleUrl = data.voiceSampleUrl
  formData.voiceId = data.voiceId
  if (data.duration !== undefined) {
    voiceDuration.value = data.duration
  }
}

function togglePlayVoiceSample() {
  if (!formData.voiceSampleUrl) return
  if (isPlayingVoice.value && currentAudio) {
    currentAudio.pause()
    isPlayingVoice.value = false
    return
  }
  if (!currentAudio || currentAudio.src !== formData.voiceSampleUrl) {
    currentAudio = new Audio(formData.voiceSampleUrl)
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

function handleClearVoiceSample() {
  if (currentAudio) {
    currentAudio.pause()
    currentAudio = null
  }
  isPlayingVoice.value = false
  formData.voiceSampleUrl = ''
  voiceDuration.value = null
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      saving.value = true
      const payload: any = { ...formData }
      const trimmedName = (payload.name || '').trim()

      payload.name = trimmedName
      if (payload.identityStatus === 'CONFIRMED') {
        payload.canonicalName = trimmedName
      } else if (payload.identityStatus === 'PARTIAL') {
        payload.displayName = trimmedName
        payload.canonicalName = ''
      } else {
        payload.canonicalName = trimmedName
      }

      delete payload.outfits
      delete payload.looks
      delete payload.defaultOutfit
      delete payload.defaultLook
      delete payload.outfitCount
      delete payload.evidences
      delete payload.createTime
      delete payload.updateTime
      delete payload.aliases
      delete payload.referenceImageUrl

      if (isEdit.value) {
        await characterApi.update(payload)
        ElMessage.success('人物角色更新成功')
      } else {
        const newId = await characterApi.create(payload)
        ElMessage.success('人物角色创建成功')
        formData.id = newId
        await loadLooks()
        emit('success', newId)
        visible.value = false
        return
      }
      visible.value = false
      emit('success')
    } catch (error) {
      console.error(error)
    } finally {
      saving.value = false
    }
  })
}

function handleClose(done?: () => void) {
  emit('cancel')
  if (currentAudio) {
    currentAudio.pause()
    currentAudio = null
  }
  isPlayingVoice.value = false
  visible.value = false
  done?.()
}

onBeforeUnmount(() => {
  if (currentAudio) {
    currentAudio.pause()
    currentAudio = null
  }
})

defineExpose({ open })
</script>

<style scoped>
:deep(.more-collapse .el-collapse-item__header) {
  background-color: #f8fafc;
  border-radius: 8px;
  padding: 0 12px;
  border: 1px solid #e2e8f0;
  height: 40px;
  line-height: 40px;
}
:deep(.more-collapse .el-collapse-item__wrap) {
  border-bottom: none;
}
:deep(.more-collapse .el-collapse-item__content) {
  padding-top: 14px;
  padding-bottom: 0;
}
</style>
