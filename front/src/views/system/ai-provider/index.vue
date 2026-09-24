<template>
  <div class="h-[calc(100vh-96px)] flex flex-col studio-card overflow-hidden">
    <!-- 顶部工作台标头 -->
    <WorkspaceHeader
      title="AI 能力接入控制台"
      subtitle="统一纳管 OpenAI 兼容服务与本地 Ollama 大模型，提供凭据安全脱敏与自适应调度熔断"
      :icon="Cpu"
    >
      <template #tag>
        <span class="studio-badge bg-[var(--brand-soft)] text-[var(--brand)] font-mono border border-[var(--brand-soft)]">
          AI 核心网关
        </span>
      </template>

      <template #actions>
        <el-button
          v-if="currentProvider"
          type="success"
          plain
          size="small"
          :icon="Connection"
          :loading="testingProviderId === currentProvider.id"
          @click="handleTestProvider(currentProvider)"
        >
          测试当前连通性
        </el-button>
        <el-button
          v-if="currentProvider"
          type="primary"
          size="small"
          :icon="Plus"
          @click="handleOpenModelDialog()"
        >
          注册新模型
        </el-button>
      </template>
    </WorkspaceHeader>

    <!-- 主控台左右分栏 -->
    <div class="flex-1 flex overflow-hidden border-t border-[var(--border-default)]">
      <!-- ========================================================================= -->
      <!-- 左侧栏：AI 提供商列表 (300px)                                             -->
      <!-- ========================================================================= -->
      <div class="w-80 shrink-0 border-r border-[var(--border-default)] flex flex-col bg-[var(--surface-muted)] select-none">
        <!-- 搜索与主操作 -->
        <div class="p-3.5 border-b border-[var(--border-default)] bg-[var(--surface)] space-y-2.5">
          <div class="flex items-center justify-between">
            <span class="text-xs font-bold text-[var(--text-primary)] tracking-wide">提供商列表</span>
            <el-button
              type="primary"
              size="small"
              link
              :icon="Plus"
              class="!text-xs"
              @click="handleOpenProviderDialog()"
            >
              接入提供商
            </el-button>
          </div>

          <el-input
            v-model="providerSearchKeyword"
            placeholder="搜索名称或编码..."
            size="small"
            clearable
            :prefix-icon="Search"
          />
        </div>

        <!-- 提供商列表流 -->
        <el-scrollbar v-loading="providerLoading" class="flex-1 p-2.5">
          <div v-if="filteredProviderList.length === 0" class="py-12 text-center text-xs text-[var(--text-muted)]">
            <WorkspaceEmptyState
              description="无匹配 AI 提供商"
              action-text="接入新提供商"
              @action="handleOpenProviderDialog()"
            />
          </div>

          <div v-else class="space-y-2">
            <div
              v-for="item in filteredProviderList"
              :key="String(item.id)"
              class="p-3 rounded-xl cursor-pointer transition-all border relative group"
              :class="currentProvider?.id === item.id
                ? 'bg-[var(--surface)] border-[var(--brand)] shadow-xs border-l-4 border-l-[var(--brand)]'
                : 'bg-transparent border-transparent hover:bg-[var(--surface-hover)] hover:border-[var(--border-default)]'"
              @click="handleSelectProvider(item)"
            >
              <div class="flex items-start justify-between gap-2 mb-1.5">
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-1.5 mb-1">
                    <span
                      class="text-xs font-bold truncate"
                      :class="currentProvider?.id === item.id ? 'text-[var(--brand)]' : 'text-[var(--text-primary)]'"
                      :title="item.providerName"
                    >
                      {{ item.providerName }}
                    </span>
                    <span class="studio-badge bg-[var(--surface-muted)] text-[var(--text-secondary)] border border-[var(--border-default)] !text-[10px] !py-0 !px-1.5">
                      {{ item.providerType === 'OPENAI' ? 'OpenAI' : 'Ollama' }}
                    </span>
                  </div>
                  <div class="text-[11px] font-mono text-[var(--text-muted)] truncate" :title="item.providerCode">
                    {{ item.providerCode }}
                  </div>
                </div>

                <!-- 状态与菜单 -->
                <div class="flex items-center gap-1 shrink-0">
                  <StatusPill
                    :status="item.status === 1 ? 'success' : 'disabled'"
                    :label="item.status === 1 ? '启用' : '停用'"
                    size="small"
                  />

                  <el-dropdown trigger="click" @command="(cmd: string) => handleProviderCommand(cmd, item)">
                    <el-button
                      link
                      size="small"
                      class="!p-1 opacity-0 group-hover:opacity-100 transition-opacity !text-[var(--text-muted)] hover:!text-[var(--text-primary)]"
                      @click.stop
                    >
                      <el-icon><MoreFilled /></el-icon>
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="test">
                          <el-icon><Connection /></el-icon>测试连通性
                        </el-dropdown-item>
                        <el-dropdown-item command="edit">
                          <el-icon><Edit /></el-icon>编辑提供商
                        </el-dropdown-item>
                        <el-dropdown-item command="delete" divided class="!text-rose-600">
                          <el-icon><Delete /></el-icon>删除提供商
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </div>

              <!-- 底部模型数与连通状态提示 -->
              <div class="flex items-center justify-between text-[11px] text-[var(--text-muted)] pt-1 border-t border-[var(--border-default)]">
                <span class="truncate max-w-[180px] font-mono">{{ item.baseUrl ? item.baseUrl.replace(/^https?:\/\//, '') : '内置地址' }}</span>
                <span class="font-mono text-[var(--text-secondary)]">
                  {{ providerModelCounts[String(item.id)] !== undefined ? `${providerModelCounts[String(item.id)]} 模型` : '就绪' }}
                </span>
              </div>
            </div>
          </div>
        </el-scrollbar>

        <!-- 左侧底部统计 -->
        <div class="p-2.5 border-t border-[var(--border-default)] bg-[var(--surface)] flex items-center justify-between text-[11px] text-[var(--text-muted)]">
          <span>共 {{ providerList.length }} 个提供商</span>
          <el-button link size="small" class="!text-[11px] !p-0" @click="fetchProviderList">
            <el-icon class="mr-0.5"><Refresh /></el-icon>刷新
          </el-button>
        </div>
      </div>

      <!-- ========================================================================= -->
      <!-- 右侧栏：提供商配置详情与模型管理大盘 (flex-1)                             -->
      <!-- ========================================================================= -->
      <div class="flex-1 flex flex-col overflow-hidden bg-[var(--surface)]">
        <!-- 未选择提供商时的空状态 -->
        <div v-if="!currentProvider" class="flex-1 flex items-center justify-center p-12">
          <WorkspaceEmptyState
            description="请在左侧选择一个 AI 提供商查看配置详情与接入模型"
            action-text="接入新提供商"
            @action="handleOpenProviderDialog()"
          />
        </div>

        <template v-else>
          <!-- 顶部基础配置与连接概览卡片 -->
          <div class="p-4 border-b border-[var(--border-default)] bg-[var(--surface)] space-y-3">
            <div class="flex items-start justify-between gap-4">
              <div class="flex items-start gap-3">
                <div class="w-10 h-10 rounded-xl bg-[var(--brand-soft)] text-[var(--brand)] flex items-center justify-center font-bold text-base shrink-0 mt-0.5">
                  <el-icon :size="22"><Cpu /></el-icon>
                </div>
                <div>
                  <div class="flex items-center gap-2">
                    <h3 class="font-bold text-base text-[var(--text-primary)]">{{ currentProvider.providerName }}</h3>
                    <span class="font-mono text-xs text-[var(--text-muted)] bg-[var(--surface-muted)] px-2 py-0.5 rounded border border-[var(--border-default)]">
                      {{ currentProvider.providerCode }}
                    </span>
                    <span class="studio-badge bg-[var(--brand-soft)] text-[var(--brand)] border border-[var(--brand-soft)]">
                      {{ currentProvider.providerType === 'OPENAI' ? 'OpenAI 规范兼容' : 'Ollama 本地接入' }}
                    </span>
                    <StatusPill
                      :status="currentProvider.status === 1 ? 'success' : 'disabled'"
                      :label="currentProvider.status === 1 ? '启用中' : '已停用'"
                      size="small"
                    />
                  </div>
                  <p class="text-xs text-[var(--text-secondary)] mt-1">
                    {{ currentProvider.remark || '暂无说明备注' }}
                  </p>
                </div>
              </div>

              <!-- 右侧快捷控制区 -->
              <div class="flex items-center gap-2 shrink-0">
                <el-button
                  type="success"
                  size="small"
                  :loading="testingProviderId === currentProvider.id"
                  @click="handleTestProvider(currentProvider)"
                >
                  <el-icon class="mr-1"><Connection /></el-icon>连通性测试
                </el-button>
                <el-button
                  size="small"
                  @click="handleOpenProviderDialog(currentProvider)"
                >
                  <el-icon class="mr-1"><Edit /></el-icon>编辑配置
                </el-button>
              </div>
            </div>

            <!-- 参数指标面板 -->
            <div class="grid grid-cols-2 md:grid-cols-4 gap-3 pt-2 text-xs bg-[var(--surface-muted)] p-3 rounded-xl border border-[var(--border-default)]">
              <div class="min-w-0">
                <div class="text-[11px] text-[var(--text-muted)] mb-0.5">Base URL (服务接入端点)</div>
                <div class="font-mono text-[var(--text-primary)] truncate" :title="currentProvider.baseUrl || '默认'">
                  {{ currentProvider.baseUrl || '-' }}
                </div>
              </div>

              <div>
                <div class="text-[11px] text-[var(--text-muted)] mb-0.5">API 凭据安全脱敏</div>
                <div class="font-mono text-[var(--text-primary)] truncate">
                  <span v-if="currentProvider.hasApiKey || currentProvider.maskedApiKey" class="text-emerald-600 font-bold">
                    {{ currentProvider.maskedApiKey || '已加密落库' }}
                  </span>
                  <span v-else class="text-[var(--text-muted)]">未配置密钥</span>
                </div>
              </div>

              <div>
                <div class="text-[11px] text-[var(--text-muted)] mb-0.5">超时与重试</div>
                <div class="font-mono text-[var(--text-primary)]">
                  {{ currentProvider.timeout || 30 }}s / 重试 {{ currentProvider.maxRetries ?? 3 }} 次
                </div>
              </div>

              <div>
                <div class="text-[11px] text-[var(--text-muted)] mb-0.5">自适应熔断保护</div>
                <div class="font-mono text-[var(--text-primary)] flex items-center gap-1.5">
                  <span
                    class="w-2 h-2 rounded-full"
                    :class="currentProvider.enableBreaker === 1 ? 'bg-emerald-500' : 'bg-slate-400'"
                  ></span>
                  <span>{{ currentProvider.enableBreaker === 1 ? `已开启 (阈值:${currentProvider.breakerThreshold || 10})` : '已关闭' }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 下方：模型列表大盘 -->
          <div class="flex-1 overflow-hidden p-4 bg-[var(--app-bg)] flex flex-col">
            <div class="flex-1 studio-card overflow-hidden flex flex-col p-3">
              <!-- 模型头部工具栏 -->
              <div class="flex items-center justify-between pb-3 border-b border-[var(--border-default)] mb-2 flex-wrap gap-2">
                <div class="flex items-center gap-2">
                  <h4 class="font-bold text-xs text-[var(--text-primary)] tracking-wide">
                    已接入 AI 模型 (共 {{ modelList.length }} 个)
                  </h4>
                  <span class="text-[11px] text-[var(--text-muted)]">
                    分镜生成、剧情拆解与台词配音调度此列表模型
                  </span>
                </div>

                <div class="flex items-center gap-2">
                  <el-input
                    v-model="modelSearchKeyword"
                    placeholder="按模型名/编码筛选..."
                    size="small"
                    clearable
                    class="!w-48"
                  >
                    <template #prefix>
                      <el-icon class="text-[var(--text-muted)]"><Search /></el-icon>
                    </template>
                  </el-input>

                  <el-button type="primary" size="small" :icon="Plus" @click="handleOpenModelDialog()">
                    添加模型
                  </el-button>
                </div>
              </div>

              <!-- 模型表格 -->
              <el-table
                v-loading="modelLoading"
                :data="filteredModelList"
                height="100%"
                size="small"
                class="w-full"
              >
                <el-table-column label="模型名称" min-width="160">
                  <template #default="{ row }">
                    <span class="font-bold text-xs text-[var(--text-primary)]">{{ row.modelName }}</span>
                  </template>
                </el-table-column>

                <el-table-column label="模型标识 (Model Code)" min-width="180">
                  <template #default="{ row }">
                    <span class="font-mono text-xs text-[var(--text-primary)] bg-[var(--surface-muted)] px-2 py-0.5 rounded border border-[var(--border-default)]">
                      {{ row.modelCode }}
                    </span>
                  </template>
                </el-table-column>

                <el-table-column label="能力类型" width="160" align="center">
                  <template #default="{ row }">
                    <span class="studio-badge bg-[var(--surface-muted)] text-[var(--text-secondary)] border border-[var(--border-default)] !text-[11px]">
                      {{ getModelTypeLabel(row.modelType) }}
                    </span>
                  </template>
                </el-table-column>

                <el-table-column label="推理参数" min-width="160">
                  <template #default="{ row }">
                    <div class="text-[11px] font-mono text-[var(--text-muted)]">
                      <span v-if="row.maxTokens">Max: {{ row.maxTokens }}</span>
                      <span v-if="row.temperature !== undefined" class="ml-2">Temp: {{ row.temperature }}</span>
                      <span v-if="!row.maxTokens && row.temperature === undefined">-</span>
                    </div>
                  </template>
                </el-table-column>

                <el-table-column label="状态" width="90" align="center">
                  <template #default="{ row }">
                    <StatusPill
                      :status="row.status === 1 ? 'success' : 'disabled'"
                      :label="row.status === 1 ? '启用' : '停用'"
                      size="small"
                    />
                  </template>
                </el-table-column>

                <el-table-column label="操作" width="130" align="center" fixed="right">
                  <template #default="{ row }">
                    <el-button type="primary" link size="small" @click="handleOpenModelDialog(row)">
                      编辑
                    </el-button>
                    <el-popconfirm
                      title="确定删除此 AI 模型？"
                      confirm-button-text="删除"
                      cancel-button-text="取消"
                      @confirm="handleDeleteModel(row.id)"
                    >
                      <template #reference>
                        <el-button type="danger" link size="small">
                          删除
                        </el-button>
                      </template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- ========================================================================= -->
    <!-- 弹窗：新增 / 编辑提供商                                                    -->
    <!-- ========================================================================= -->
    <el-dialog
      v-model="providerDialogVisible"
      :title="providerForm.id ? '修改 AI 提供商配置' : '接入新 AI 提供商'"
      width="640px"
      append-to-body
      destroy-on-close
      class="studio-dialog"
    >
      <el-form
        ref="providerFormRef"
        :model="providerForm"
        :rules="providerRules"
        label-width="120px"
        size="default"
        class="space-y-3 pt-2"
      >
        <el-divider content-position="left" class="!my-2 text-xs font-bold text-[var(--text-muted)]">
          基础信息
        </el-divider>

        <el-form-item label="提供商编码" prop="providerCode">
          <el-input
            v-model="providerForm.providerCode"
            placeholder="如 deepseek / openai / ollama_local"
            :disabled="!!providerForm.id"
            class="font-mono"
          />
        </el-form-item>

        <el-form-item label="提供商名称" prop="providerName">
          <el-input v-model="providerForm.providerName" placeholder="如 DeepSeek 官方网关" />
        </el-form-item>

        <el-form-item label="接入协议" prop="providerType">
          <el-radio-group v-model="providerForm.providerType">
            <el-radio value="OPENAI">OpenAI 规范兼容</el-radio>
            <el-radio value="OLLAMA">Ollama 本地/私有</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-divider content-position="left" class="!my-2 text-xs font-bold text-[var(--text-muted)]">
          接口与认证凭据
        </el-divider>

        <el-form-item label="Base URL" prop="baseUrl">
          <el-input
            v-model="providerForm.baseUrl"
            placeholder="如 https://api.deepseek.com/v1 或 http://localhost:11434"
            class="font-mono"
          />
        </el-form-item>

        <el-form-item label="API Key" prop="apiKey">
          <el-input
            v-model="providerForm.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="providerForm.id ? (providerFormCurrentMaskedKey ? `保留原密钥 (${providerFormCurrentMaskedKey})` : '留空保留原密钥') : '输入 API Key 密钥'"
            class="font-mono"
          />
          <div v-if="providerForm.id" class="text-[11px] text-[var(--text-muted)] mt-1">
            出于安全规范，已有密钥已脱敏保护；若无需修改请保持输入框为空，系统不会覆盖原密钥。
          </div>
        </el-form-item>

        <el-divider content-position="left" class="!my-2 text-xs font-bold text-[var(--text-muted)]">
          调度与熔断保护
        </el-divider>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="超时时间 (秒)" prop="timeout">
              <el-input-number v-model="providerForm.timeout" :min="5" :max="600" class="!w-full" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最大重试次数" prop="maxRetries">
              <el-input-number v-model="providerForm.maxRetries" :min="0" :max="10" class="!w-full" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="启用自适应熔断">
              <el-switch
                :model-value="providerForm.enableBreaker === 1"
                @update:model-value="(val: any) => providerForm.enableBreaker = val ? 1 : 0"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="providerForm.enableBreaker === 1">
            <el-form-item label="连续失败阈值">
              <el-input-number v-model="providerForm.breakerThreshold" :min="1" :max="100" class="!w-full" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="providerForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="说明备注" prop="remark">
          <el-input
            v-model="providerForm.remark"
            type="textarea"
            :rows="2"
            placeholder="填写该提供商备注说明..."
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button @click="providerDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="providerSubmitting" @click="handleSubmitProvider">
            确定保存
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- ========================================================================= -->
    <!-- 弹窗：新增 / 编辑模型                                                      -->
    <!-- ========================================================================= -->
    <el-dialog
      v-model="modelDialogVisible"
      :title="modelForm.id ? '修改 AI 模型' : '注册新 AI 模型'"
      width="600px"
      append-to-body
      destroy-on-close
      class="studio-dialog"
    >
      <el-form
        ref="modelFormRef"
        :model="modelForm"
        :rules="modelRules"
        label-width="120px"
        size="default"
        class="space-y-3 pt-2"
      >
        <el-form-item label="所属提供商">
          <el-input :model-value="`${currentProvider?.providerName} (${currentProvider?.providerCode})`" disabled class="font-mono" />
        </el-form-item>

        <el-form-item label="模型名称" prop="modelName">
          <el-input v-model="modelForm.modelName" placeholder="如 DeepSeek V3 / SDXL Base" />
        </el-form-item>

        <el-form-item label="模型编码" prop="modelCode">
          <el-input v-model="modelForm.modelCode" placeholder="如 deepseek-chat / sdxl" class="font-mono" />
        </el-form-item>

        <el-form-item label="模型类型" prop="modelType">
          <el-select
            v-model="modelForm.modelType"
            placeholder="选择类型"
            class="w-full"
            @change="handleModelTypeChange"
          >
            <el-option label="CHAT (文本对话/剧本拆解)" value="CHAT" />
            <el-option label="TXT2IMG (文生图)" value="TXT2IMG" />
            <el-option label="IMG2IMG (图生图)" value="IMG2IMG" />
            <el-option label="TXT_IMG2IMG (文生图/图生图)" value="TXT_IMG2IMG" />
            <el-option label="TXT2VIDEO_FIRST_LAST (文生视频/首尾帧)" value="TXT2VIDEO_FIRST_LAST" />
            <el-option label="TXT2VIDEO_REF (文生视频/图参考)" value="TXT2VIDEO_REF" />
            <el-option label="TTS (TTS语音台词配音)" value="TTS" />
            <el-option label="LIP_SYNC (音画口型同步)" value="LIP_SYNC" />
            <el-option label="VIDEO_UPSCALE (视频超分辨率增强)" value="VIDEO_UPSCALE" />
            <el-option label="FRAME_INTERPOLATION (视频平滑补帧)" value="FRAME_INTERPOLATION" />
            <el-option label="EMBEDDING (向量嵌入)" value="EMBEDDING" />
          </el-select>
        </el-form-item>

        <!-- 视频后处理专属结构化配置 -->
        <div v-if="isVideoProcessingType" class="p-3 bg-slate-50 rounded-lg border border-slate-200 space-y-2.5 mb-3">
          <div class="text-xs font-bold text-slate-700 flex items-center justify-between">
            <span>⚙️ 视频后处理网关参数 (Params Configuration)</span>
            <span class="text-[11px] text-slate-400 font-normal">自动生成受控 paramsJson</span>
          </div>

          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="引擎模型文件名" label-width="110px">
                <el-input v-model="vpForm.engineModel" placeholder="如 RealESRGAN_x2plus.pth 或 rife49.pth" class="font-mono text-xs" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="生成路径前缀" label-width="110px">
                <el-input v-model="vpForm.filenamePrefix" placeholder="如 video_upscale/realesrgan_x2" class="font-mono text-xs" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="12" v-if="modelForm.modelType === 'VIDEO_UPSCALE'">
            <el-col :span="12">
              <el-form-item label="默认放大倍率" label-width="110px">
                <el-input-number v-model="vpForm.defaultScale" :min="2" :max="4" class="!w-full" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="允许倍率列表" label-width="110px">
                <el-input v-model="vpFormAllowedScalesStr" placeholder="如 2, 4" class="font-mono text-xs" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="12" v-if="modelForm.modelType === 'FRAME_INTERPOLATION'">
            <el-col :span="12">
              <el-form-item label="默认插帧倍率" label-width="110px">
                <el-input-number v-model="vpForm.defaultMultiplier" :min="2" :max="4" class="!w-full" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="允许倍率列表" label-width="110px">
                <el-input v-model="vpFormAllowedMultipliersStr" placeholder="如 2, 4" class="font-mono text-xs" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="12">
            <el-col :span="8">
              <el-form-item label="默认CRF" label-width="80px">
                <el-input-number v-model="vpForm.defaultCrf" :min="1" :max="51" class="!w-full" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="最小CRF" label-width="80px">
                <el-input-number v-model="vpForm.minCrf" :min="1" :max="51" class="!w-full" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="最大CRF" label-width="80px">
                <el-input-number v-model="vpForm.maxCrf" :min="1" :max="51" class="!w-full" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="12" v-if="modelForm.modelType === 'FRAME_INTERPOLATION'">
            <el-col :span="8">
              <el-form-item label="默认显存周期" label-width="90px">
                <el-input-number v-model="vpForm.defaultClearCacheFrames" :min="10" :max="1000" class="!w-full" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="最小周期" label-width="80px">
                <el-input-number v-model="vpForm.minClearCacheFrames" :min="10" :max="1000" class="!w-full" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="最大周期" label-width="80px">
                <el-input-number v-model="vpForm.maxClearCacheFrames" :min="10" :max="1000" class="!w-full" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="默认保留音频" label-width="110px">
            <el-switch v-model="vpForm.defaultPreserveAudio" active-text="保留" inactive-text="静音" />
          </el-form-item>
        </div>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="最大 Tokens" prop="maxTokens">
              <el-input-number v-model="modelForm.maxTokens" :min="1" :max="1000000" class="!w-full" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="温度 (Temp)" prop="temperature">
              <el-input-number v-model="modelForm.temperature" :min="0" :max="2" :step="0.1" class="!w-full" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="modelForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="说明备注" prop="remark">
          <el-input
            v-model="modelForm.remark"
            type="textarea"
            :rows="2"
            placeholder="模型业务用途与能力说明..."
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button @click="modelDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="modelSubmitting" @click="handleSubmitModel">
            确定保存
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Cpu,
  Search,
  Refresh,
  Plus,
  Edit,
  Delete,
  MoreFilled,
  Connection
} from '@element-plus/icons-vue'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import StatusPill from '@/components/status/StatusPill.vue'
import { aiProviderApi, aiModelApi } from '@/api/ai-provider'
import type {
  AiProviderVO,
  AiProviderDTO,
  AiModel,
  AiModelDTO,
  ModelType
} from '@/types/ai-provider'

// ======================== 1. 提供商列表管理 ========================
const providerLoading = ref(false)
const providerList = ref<AiProviderVO[]>([])
const providerSearchKeyword = ref('')
const currentProvider = ref<AiProviderVO | null>(null)
const providerModelCounts = reactive<Record<string, number>>({})

const filteredProviderList = computed(() => {
  if (!providerSearchKeyword.value.trim()) return providerList.value
  const kw = providerSearchKeyword.value.trim().toLowerCase()
  return providerList.value.filter(
    p => p.providerName.toLowerCase().includes(kw) || p.providerCode.toLowerCase().includes(kw)
  )
})

const fetchProviderList = async () => {
  providerLoading.value = true
  try {
    const res = await aiProviderApi.getPage({ pageNum: 1, pageSize: 100 })
    providerList.value = res.records || []
    if (providerList.value.length > 0) {
      if (!currentProvider.value) {
        handleSelectProvider(providerList.value[0])
      } else {
        const found = providerList.value.find(p => String(p.id) === String(currentProvider.value?.id))
        if (found) currentProvider.value = found
      }
      // 预热统计各提供商模型数
      for (const p of providerList.value) {
        aiProviderApi.getModelList(p.id).then(list => {
          providerModelCounts[String(p.id)] = list?.length || 0
        }).catch(() => {})
      }
    } else {
      currentProvider.value = null
      modelList.value = []
    }
  } finally {
    providerLoading.value = false
  }
}

const handleSelectProvider = (item: AiProviderVO) => {
  currentProvider.value = item
  modelSearchKeyword.value = ''
  fetchModelList()
}

const handleProviderCommand = (cmd: string, item: AiProviderVO) => {
  if (cmd === 'edit') {
    handleOpenProviderDialog(item)
  } else if (cmd === 'test') {
    handleTestProvider(item)
  } else if (cmd === 'delete') {
    ElMessageBox.confirm(
      `确定删除 AI 提供商 [${item.providerName}] 吗？请确保没有关键业务正调用该提供商。`,
      '删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    ).then(() => {
      handleDeleteProvider(item.id)
    }).catch(() => {})
  }
}

const testingProviderId = ref<number | string | null>(null)
const handleTestProvider = async (row: AiProviderVO) => {
  testingProviderId.value = row.id
  try {
    await aiProviderApi.test(row.id)
    ElMessage.success(`提供商 [${row.providerName}] 连通性测试通过，网关服务响应正常！`)
  } catch (e: any) {
    ElMessage.error(e?.message || '连通性测试失败，请检查网络或 Base URL / API Key 配置')
  } finally {
    testingProviderId.value = null
  }
}

const handleDeleteProvider = async (id?: number | string) => {
  if (!id) return
  try {
    await aiProviderApi.delete(id)
    ElMessage.success('删除提供商成功')
    if (String(currentProvider.value?.id) === String(id)) {
      currentProvider.value = null
    }
    fetchProviderList()
  } catch (e: any) {
    ElMessage.error(e?.message || '删除提供商失败')
  }
}

// 提供商弹窗
const providerDialogVisible = ref(false)
const providerSubmitting = ref(false)
const providerFormRef = ref<FormInstance>()
const providerFormCurrentMaskedKey = ref('')
const providerForm = reactive<AiProviderDTO>({
  id: undefined,
  providerCode: '',
  providerName: '',
  providerType: 'OPENAI',
  apiKey: '',
  baseUrl: '',
  timeout: 30,
  maxRetries: 3,
  enableBreaker: 1,
  breakerThreshold: 10,
  breakerTimeout: 30,
  status: 1,
  remark: ''
})

const providerRules: FormRules = {
  providerCode: [
    { required: true, message: '请输入提供商编码', trigger: 'blur' },
    { min: 2, max: 64, message: '长度在 2 到 64 个字符', trigger: 'blur' }
  ],
  providerName: [
    { required: true, message: '请输入提供商名称', trigger: 'blur' },
    { min: 2, max: 100, message: '长度在 2 到 100 个字符', trigger: 'blur' }
  ],
  providerType: [{ required: true, message: '请选择接入类型', trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

const handleOpenProviderDialog = async (row?: AiProviderVO) => {
  if (row && row.id) {
    try {
      const detail = await aiProviderApi.getDetail(row.id)
      providerForm.id = detail.id
      providerForm.providerCode = detail.providerCode
      providerForm.providerName = detail.providerName
      providerForm.providerType = detail.providerType
      providerForm.apiKey = '' // 安全规范：编辑时默认留空
      providerFormCurrentMaskedKey.value = detail.maskedApiKey || ''
      providerForm.baseUrl = detail.baseUrl || ''
      providerForm.timeout = detail.timeout ?? 30
      providerForm.maxRetries = detail.maxRetries ?? 3
      providerForm.enableBreaker = detail.enableBreaker ?? 1
      providerForm.breakerThreshold = detail.breakerThreshold ?? 10
      providerForm.breakerTimeout = detail.breakerTimeout ?? 30
      providerForm.status = detail.status
      providerForm.remark = detail.remark || ''
    } catch {
      providerForm.id = row.id
      providerForm.providerCode = row.providerCode
      providerForm.providerName = row.providerName
      providerForm.providerType = row.providerType
      providerForm.apiKey = ''
      providerFormCurrentMaskedKey.value = row.maskedApiKey || ''
      providerForm.baseUrl = row.baseUrl || ''
      providerForm.timeout = row.timeout ?? 30
      providerForm.maxRetries = row.maxRetries ?? 3
      providerForm.enableBreaker = row.enableBreaker ?? 1
      providerForm.breakerThreshold = row.breakerThreshold ?? 10
      providerForm.breakerTimeout = row.breakerTimeout ?? 30
      providerForm.status = row.status
      providerForm.remark = row.remark || ''
    }
  } else {
    providerForm.id = undefined
    providerForm.providerCode = ''
    providerForm.providerName = ''
    providerForm.providerType = 'OPENAI'
    providerForm.apiKey = ''
    providerFormCurrentMaskedKey.value = ''
    providerForm.baseUrl = ''
    providerForm.timeout = 30
    providerForm.maxRetries = 3
    providerForm.enableBreaker = 1
    providerForm.breakerThreshold = 10
    providerForm.breakerTimeout = 30
    providerForm.status = 1
    providerForm.remark = ''
  }
  providerDialogVisible.value = true
}

const handleSubmitProvider = async () => {
  if (!providerFormRef.value) return
  await providerFormRef.value.validate()

  providerSubmitting.value = true
  try {
    const payload: AiProviderDTO = { ...providerForm }
    // 如果编辑时未重新输入 API Key，则不提交空字符串避免覆盖原密文
    if (payload.id && !payload.apiKey) {
      delete payload.apiKey
    }

    if (payload.id) {
      await aiProviderApi.update(payload)
      ElMessage.success('修改 AI 提供商成功')
    } else {
      await aiProviderApi.create(payload)
      ElMessage.success('新增 AI 提供商成功')
    }
    providerDialogVisible.value = false
    fetchProviderList()
  } finally {
    providerSubmitting.value = false
  }
}

// ======================== 2. 关联模型管理 ========================
const modelLoading = ref(false)
const modelList = ref<AiModel[]>([])
const modelSearchKeyword = ref('')

const filteredModelList = computed(() => {
  if (!modelSearchKeyword.value.trim()) return modelList.value
  const kw = modelSearchKeyword.value.trim().toLowerCase()
  return modelList.value.filter(
    m => m.modelName.toLowerCase().includes(kw) || m.modelCode.toLowerCase().includes(kw)
  )
})

const fetchModelList = async () => {
  if (!currentProvider.value) return
  modelLoading.value = true
  try {
    const res = await aiModelApi.getPage({
      providerId: currentProvider.value.id,
      pageNum: 1,
      pageSize: 100
    })
    modelList.value = res.records || []
    providerModelCounts[String(currentProvider.value.id)] = modelList.value.length
  } finally {
    modelLoading.value = false
  }
}

const handleDeleteModel = async (id?: number | string) => {
  if (!id) return
  try {
    await aiModelApi.delete(id)
    ElMessage.success('删除 AI 模型成功')
    fetchModelList()
  } catch (e: any) {
    ElMessage.error(e?.message || '删除模型失败')
  }
}

// 模型弹窗
const modelDialogVisible = ref(false)
const modelSubmitting = ref(false)
const modelFormRef = ref<FormInstance>()
const modelForm = reactive<AiModelDTO>({
  id: undefined,
  providerId: '',
  modelCode: '',
  modelName: '',
  modelType: 'CHAT',
  temperature: undefined,
  maxTokens: undefined,
  topP: undefined,
  paramsJson: '',
  maxImages: undefined,
  maxAudios: undefined,
  maxVideos: undefined,
  sortOrder: 0,
  status: 1,
  remark: ''
})

const isVideoProcessingType = computed(() => {
  return modelForm.modelType === 'VIDEO_UPSCALE' || modelForm.modelType === 'FRAME_INTERPOLATION'
})

const vpForm = reactive({
  engineModel: '',
  filenamePrefix: '',
  defaultScale: 2,
  defaultMultiplier: 2,
  defaultCrf: 16,
  minCrf: 10,
  maxCrf: 30,
  defaultClearCacheFrames: 100,
  minClearCacheFrames: 50,
  maxClearCacheFrames: 300,
  defaultPreserveAudio: true
})

const vpFormAllowedScalesStr = ref('2, 4')
const vpFormAllowedMultipliersStr = ref('2, 4')

const handleModelTypeChange = (type?: string) => {
  if (type === 'VIDEO_UPSCALE') {
    if (!vpForm.engineModel) vpForm.engineModel = 'RealESRGAN_x2plus.pth'
    if (!vpForm.filenamePrefix) vpForm.filenamePrefix = 'video_upscale/realesrgan_x2'
    vpForm.defaultScale = 2
    vpForm.defaultCrf = 16
    vpForm.minCrf = 10
    vpForm.maxCrf = 30
    vpFormAllowedScalesStr.value = '2, 4'
  } else if (type === 'FRAME_INTERPOLATION') {
    if (!vpForm.engineModel) vpForm.engineModel = 'rife49.pth'
    if (!vpForm.filenamePrefix) vpForm.filenamePrefix = 'video_rife/rife_48fps'
    vpForm.defaultMultiplier = 2
    vpForm.defaultCrf = 19
    vpForm.minCrf = 12
    vpForm.maxCrf = 28
    vpForm.defaultClearCacheFrames = 100
    vpForm.minClearCacheFrames = 50
    vpForm.maxClearCacheFrames = 300
    vpFormAllowedMultipliersStr.value = '2, 4'
  }
}

function initVpFormFromParamsJson(paramsJson?: string) {
  if (paramsJson) {
    try {
      const parsed = JSON.parse(paramsJson)
      if (parsed.videoProcessing) {
        const vp = parsed.videoProcessing
        vpForm.engineModel = vp.engineModel || ''
        vpForm.filenamePrefix = vp.filenamePrefix || ''
        vpForm.defaultScale = vp.defaultScale ?? 2
        vpFormAllowedScalesStr.value = Array.isArray(vp.allowedScales) ? vp.allowedScales.join(', ') : '2, 4'
        vpForm.defaultMultiplier = vp.defaultMultiplier ?? 2
        vpFormAllowedMultipliersStr.value = Array.isArray(vp.allowedMultipliers) ? vp.allowedMultipliers.join(', ') : '2, 4'
        vpForm.defaultCrf = vp.defaultCrf ?? 16
        vpForm.minCrf = vp.minCrf ?? 10
        vpForm.maxCrf = vp.maxCrf ?? 30
        vpForm.defaultClearCacheFrames = vp.defaultClearCacheFrames ?? 100
        vpForm.minClearCacheFrames = vp.minClearCacheFrames ?? 50
        vpForm.maxClearCacheFrames = vp.maxClearCacheFrames ?? 300
        vpForm.defaultPreserveAudio = vp.defaultPreserveAudio ?? true
        return
      }
    } catch {
      // ignore
    }
  }
  handleModelTypeChange(modelForm.modelType)
}

const modelRules: FormRules = {
  modelCode: [
    { required: true, message: '请输入模型标识', trigger: 'blur' },
    { max: 64, message: '长度不可超过 64 个字符', trigger: 'blur' }
  ],
  modelName: [
    { required: true, message: '请输入模型展示名称', trigger: 'blur' },
    { max: 100, message: '长度不可超过 100 个字符', trigger: 'blur' }
  ],
  modelType: [{ required: true, message: '请选择模型能力类型', trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

const handleOpenModelDialog = async (row?: AiModel) => {
  if (!currentProvider.value) return
  if (row && row.id) {
    try {
      const detail = await aiModelApi.getDetail(row.id)
      modelForm.id = detail.id
      modelForm.providerId = String(detail.providerId)
      modelForm.modelCode = detail.modelCode
      modelForm.modelName = detail.modelName
      modelForm.modelType = detail.modelType || 'CHAT'
      modelForm.temperature = detail.temperature
      modelForm.maxTokens = detail.maxTokens
      modelForm.topP = detail.topP
      modelForm.paramsJson = detail.paramsJson || ''
      modelForm.maxImages = detail.maxImages
      modelForm.maxAudios = detail.maxAudios
      modelForm.maxVideos = detail.maxVideos
      modelForm.sortOrder = detail.sortOrder ?? 0
      modelForm.status = detail.status
      modelForm.remark = detail.remark || ''
    } catch {
      modelForm.id = row.id
      modelForm.providerId = String(row.providerId)
      modelForm.modelCode = row.modelCode
      modelForm.modelName = row.modelName
      modelForm.modelType = row.modelType || 'CHAT'
      modelForm.temperature = row.temperature
      modelForm.maxTokens = row.maxTokens
      modelForm.topP = row.topP
      modelForm.paramsJson = row.paramsJson || ''
      modelForm.maxImages = row.maxImages
      modelForm.maxAudios = row.maxAudios
      modelForm.maxVideos = row.maxVideos
      modelForm.sortOrder = row.sortOrder ?? 0
      modelForm.status = row.status
      modelForm.remark = row.remark || ''
    }
  } else {
    modelForm.id = undefined
    modelForm.providerId = String(currentProvider.value.id)
    modelForm.modelCode = ''
    modelForm.modelName = ''
    modelForm.modelType = 'CHAT'
    modelForm.temperature = undefined
    modelForm.maxTokens = undefined
    modelForm.topP = undefined
    modelForm.paramsJson = ''
    modelForm.maxImages = undefined
    modelForm.maxAudios = undefined
    modelForm.maxVideos = undefined
    modelForm.sortOrder = 0
    modelForm.status = 1
    modelForm.remark = ''
  }
  initVpFormFromParamsJson(modelForm.paramsJson)
  modelDialogVisible.value = true
}

const handleSubmitModel = async () => {
  if (!modelFormRef.value) return
  await modelFormRef.value.validate()

  if (isVideoProcessingType.value) {
    if (!vpForm.engineModel.trim()) {
      ElMessage.warning('视频后处理模型必须指定底层引擎模型文件名 (engineModel)')
      return
    }
    if (!vpForm.filenamePrefix.trim()) {
      ElMessage.warning('视频后处理模型必须指定生成文件名前缀 (filenamePrefix)')
      return
    }

    const allowedScales = vpFormAllowedScalesStr.value
      ? vpFormAllowedScalesStr.value.split(',').map(s => parseInt(s.trim())).filter(n => !isNaN(n))
      : [2, 4]
    const allowedMultipliers = vpFormAllowedMultipliersStr.value
      ? vpFormAllowedMultipliersStr.value.split(',').map(s => parseInt(s.trim())).filter(n => !isNaN(n))
      : [2, 4]

    let baseObj: any = {}
    if (modelForm.paramsJson) {
      try { baseObj = JSON.parse(modelForm.paramsJson) } catch {}
    }
    baseObj.videoProcessing = {
      engineModel: vpForm.engineModel.trim(),
      filenamePrefix: vpForm.filenamePrefix.trim(),
      defaultScale: modelForm.modelType === 'VIDEO_UPSCALE' ? vpForm.defaultScale : undefined,
      allowedScales: modelForm.modelType === 'VIDEO_UPSCALE' ? allowedScales : undefined,
      defaultMultiplier: modelForm.modelType === 'FRAME_INTERPOLATION' ? vpForm.defaultMultiplier : undefined,
      allowedMultipliers: modelForm.modelType === 'FRAME_INTERPOLATION' ? allowedMultipliers : undefined,
      defaultCrf: vpForm.defaultCrf,
      minCrf: vpForm.minCrf,
      maxCrf: vpForm.maxCrf,
      defaultClearCacheFrames: modelForm.modelType === 'FRAME_INTERPOLATION' ? vpForm.defaultClearCacheFrames : undefined,
      minClearCacheFrames: modelForm.modelType === 'FRAME_INTERPOLATION' ? vpForm.minClearCacheFrames : undefined,
      maxClearCacheFrames: modelForm.modelType === 'FRAME_INTERPOLATION' ? vpForm.maxClearCacheFrames : undefined,
      defaultPreserveAudio: vpForm.defaultPreserveAudio
    }
    modelForm.paramsJson = JSON.stringify(baseObj, null, 2)
  }

  modelSubmitting.value = true
  try {
    if (modelForm.id) {
      await aiModelApi.update(modelForm)
      ElMessage.success('修改 AI 模型成功')
    } else {
      await aiModelApi.create(modelForm)
      ElMessage.success('新增 AI 模型成功')
    }
    modelDialogVisible.value = false
    fetchModelList()
  } finally {
    modelSubmitting.value = false
  }
}

function getModelTypeLabel(type?: ModelType | string) {
  switch (type) {
    case 'CHAT': return '文本对话 / 拆解'
    case 'TXT2IMG': return '文生图'
    case 'IMG2IMG': return '图生图'
    case 'TXT_IMG2IMG': return '文/图生图双模'
    case 'TXT2VIDEO_FIRST_LAST': return '视频 / 首尾帧'
    case 'TXT2VIDEO_REF': return '视频 / 图参考'
    case 'TTS': return '语音配音'
    case 'LIP_SYNC': return '音画口型同步'
    case 'VIDEO_UPSCALE': return '视频超分'
    case 'FRAME_INTERPOLATION': return '视频补帧'
    case 'EMBEDDING': return '向量嵌入'
    case 'IMAGE': return '图像生成'
    default: return type || '通用模型'
  }
}

onMounted(() => {
  fetchProviderList()
})
</script>

<style scoped>
</style>
