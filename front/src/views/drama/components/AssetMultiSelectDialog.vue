<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="860px"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div class="space-y-3">
      <!-- 顶部提示横幅 -->
      <div class="p-3 bg-indigo-50/70 border border-indigo-100 rounded-xl flex items-center justify-between text-xs text-indigo-950">
        <div class="flex items-center gap-2">
          <span class="text-base">📦</span>
          <span>
            从剧作资产库多选参考图：可同时勾选<strong>人物各装造、道具与场景</strong>。
          </span>
        </div>
        <div class="flex items-center gap-2 font-mono">
          <el-tag size="small" :type="selectedItems.length >= maxSelectable ? 'danger' : 'primary'" effect="plain">
            本次已选 {{ selectedItems.length }} / 最多可选 {{ maxSelectable }} 张
          </el-tag>
        </div>
      </div>

      <!-- 工具栏：分类筛选与搜索 -->
      <div class="flex items-center justify-between gap-3 flex-wrap bg-slate-50 p-2.5 rounded-lg border border-slate-200">
        <el-radio-group v-model="activeCategory" size="small">
          <el-radio-button value="ALL">
            🌟 全部 ({{ filteredList.length }})
          </el-radio-button>
          <el-radio-button value="CHARACTER">
            👥 人物与造型 ({{ categoryCounts.character }})
          </el-radio-button>
          <el-radio-button value="PROP">
            🗡️ 核心道具 ({{ categoryCounts.prop }})
          </el-radio-button>
          <el-radio-button value="SCENE">
            🏞️ 场景资产 ({{ categoryCounts.scene }})
          </el-radio-button>
        </el-radio-group>

        <div class="flex items-center gap-2 w-64">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索资产名称..."
            size="small"
            clearable
            :prefix-icon="Search"
          />
        </div>
      </div>

      <!-- 素材瀑布/网格区 -->
      <div v-loading="loading" class="h-[460px] overflow-y-auto pr-1 border border-slate-100 rounded-lg p-2 bg-slate-50/30 custom-scrollbar">
        <div v-if="filteredList.length === 0" class="h-full flex flex-col items-center justify-center text-xs text-slate-400 gap-2">
          <span class="text-3xl">📭</span>
          <span>未检索到匹配的资产图</span>
        </div>

        <div v-else class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
          <div
            v-for="item in filteredList"
            :key="item.uniqueKey"
            class="relative rounded-xl border transition-all overflow-hidden bg-white group cursor-pointer select-none flex flex-col shadow-sm"
            :class="[
              isSelected(item) ? 'border-indigo-600 ring-2 ring-indigo-500/20 shadow-md' : 'border-slate-200 hover:border-indigo-300',
              isItemDisabled(item) ? 'opacity-50 cursor-not-allowed' : ''
            ]"
            @click="toggleItem(item)"
          >
            <!-- 缩略图容器 -->
            <div class="h-32 bg-slate-900 overflow-hidden flex items-center justify-center relative">
              <el-image
                :src="item.imageUrl"
                fit="cover"
                loading="lazy"
                class="w-full h-full pointer-events-none"
              />
              <!-- 类别徽章 -->
              <div class="absolute top-1.5 left-1.5">
                <el-tag size="small" effect="dark" :type="getTagType(item.sourceType)" class="!px-1.5 !text-[10px] !h-5 leading-none">
                  {{ getTagLabel(item.sourceType) }}
                </el-tag>
              </div>

              <!-- 右上角勾选状态指示器 -->
              <div class="absolute top-1.5 right-1.5">
                <div
                  v-if="isSelected(item)"
                  class="w-5 h-5 rounded-full bg-indigo-600 text-white flex items-center justify-center text-xs shadow font-bold"
                >
                  ✓
                </div>
                <div
                  v-else-if="isAlreadyAdded(item)"
                  class="px-1.5 py-0.5 rounded bg-black/60 text-white text-[9px] font-mono"
                >
                  已在列表
                </div>
                <div
                  v-else
                  class="w-5 h-5 rounded-full border border-white/80 bg-black/30 group-hover:border-white flex items-center justify-center"
                />
              </div>
            </div>

            <!-- 名称文本说明 -->
            <div class="p-2 text-xs flex flex-col gap-0.5">
              <div class="font-bold text-slate-800 truncate" :title="item.name">
                {{ item.name }}
              </div>
              <div v-if="item.subTitle" class="text-[10px] text-slate-400 truncate" :title="item.subTitle">
                {{ item.subTitle }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部操作栏 -->
    <template #footer>
      <div class="flex items-center justify-between">
        <div class="text-xs text-slate-500 flex items-center gap-3">
          <span>当前已勾选: <strong class="text-indigo-600 font-mono">{{ selectedItems.length }}</strong> / 上限 {{ maxSelectable }} 张</span>
          <el-button v-if="selectedItems.length > 0" size="small" link type="danger" @click="selectedItems = []">
            清空已选
          </el-button>
        </div>
        <div class="flex items-center gap-2">
          <el-button @click="visible = false">取消</el-button>
          <el-button
            type="primary"
            :disabled="selectedItems.length === 0"
            @click="handleConfirm"
          >
            确认引用 ({{ selectedItems.length }})
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { sceneApi } from '@/api/res-scene'
import { characterApi } from '@/api/res-character'
import { propApi } from '@/api/res-prop'

export interface AssetSelectItem {
  uniqueKey: string
  name: string
  subTitle?: string
  imageUrl: string
  sourceType: 'SCENE' | 'CHARACTER_REFERENCE' | 'CHARACTER' | 'PROP'
  sourceId?: string | number
  characterId?: string | number
  lookId?: string | number
  referenceRole?: string
}

const emit = defineEmits<{
  (e: 'confirm', items: AssetSelectItem[]): void
}>()

const visible = ref(false)
const loading = ref(false)
const maxSelectable = ref(5)
const dialogTitle = ref('多选引用资产库图片 (人物/道具/场景)')
const alreadySelectedUrls = ref<string[]>([])

const activeCategory = ref<'ALL' | 'CHARACTER' | 'PROP' | 'SCENE'>('ALL')
const searchKeyword = ref('')

const allAssets = ref<AssetSelectItem[]>([])
const selectedItems = ref<AssetSelectItem[]>([])

// 统计各分类数量
const categoryCounts = computed(() => {
  let char = 0
  let prop = 0
  let scene = 0
  for (const item of allAssets.value) {
    if (item.sourceType === 'CHARACTER' || item.sourceType === 'CHARACTER_REFERENCE') char++
    else if (item.sourceType === 'PROP') prop++
    else if (item.sourceType === 'SCENE') scene++
  }
  return { character: char, prop, scene }
})

// 过滤后的列表
const filteredList = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  return allAssets.value.filter((item) => {
    if (activeCategory.value !== 'ALL' && item.sourceType !== activeCategory.value) {
      return false
    }
    if (kw) {
      const matchName = item.name.toLowerCase().includes(kw)
      const matchSub = item.subTitle ? item.subTitle.toLowerCase().includes(kw) : false
      if (!matchName && !matchSub) return false
    }
    return true
  })
})

function getTagLabel(type: string) {
  switch (type) {
    case 'CHARACTER':
    case 'CHARACTER_REFERENCE': return '人物'
    case 'PROP': return '道具'
    case 'SCENE': return '场景'
    default: return '资产'
  }
}

function getTagType(type: string) {
  switch (type) {
    case 'CHARACTER': return 'warning'
    case 'PROP': return 'danger'
    case 'SCENE': return 'success'
    default: return 'info'
  }
}

function isSelected(item: AssetSelectItem) {
  return selectedItems.value.some((x) => x.uniqueKey === item.uniqueKey)
}

function isAlreadyAdded(item: AssetSelectItem) {
  return alreadySelectedUrls.value.includes(item.imageUrl)
}

function isItemDisabled(item: AssetSelectItem) {
  if (isAlreadyAdded(item)) return true
  if (selectedItems.value.length >= maxSelectable.value && !isSelected(item)) {
    return true
  }
  return false
}

function toggleItem(item: AssetSelectItem) {
  if (isAlreadyAdded(item)) {
    ElMessage.info('该参考图已在当前分镜参考列表中')
    return
  }
  const idx = selectedItems.value.findIndex((x) => x.uniqueKey === item.uniqueKey)
  if (idx >= 0) {
    selectedItems.value.splice(idx, 1)
  } else {
    if (selectedItems.value.length >= maxSelectable.value) {
      ElMessage.warning(`本次最多只能选择 ${maxSelectable.value} 张参考图`)
      return
    }
    selectedItems.value.push(item)
  }
}

async function loadAssets(dramaId?: string | number) {
  loading.value = true
  const items: AssetSelectItem[] = []

  try {
    const [sceneList, charList, pList] = await Promise.all([
      sceneApi.getOptions(dramaId).catch(() => []),
      characterApi.getOptions(dramaId).catch(() => []),
      propApi.getOptions(dramaId).catch(() => [])
    ])

    // 1. 场景
    for (const s of sceneList || []) {
      if (s.coverUrl) {
        items.push({
          uniqueKey: `SCENE_${s.id}`,
          name: s.name,
          subTitle: '场景主视觉封面',
          imageUrl: s.coverUrl,
          sourceType: 'SCENE',
          sourceId: s.id
        })
      }
    }

    // 2. 人物与造型
    for (const ch of charList || []) {
      const charImg = ch.referenceImageUrl || ch.avatarUrl
      if (charImg) {
        items.push({
          uniqueKey: `CHAR_IDENTITY_${ch.id}`,
          name: `${ch.name} (人物设定图)`,
          subTitle: '人物稳定外貌身份',
          imageUrl: charImg,
          sourceType: 'CHARACTER',
          sourceId: ch.id,
          characterId: ch.id,
          referenceRole: 'IDENTITY'
        })
      }

      // 人物下各造型
      if (ch.outfits && ch.outfits.length > 0) {
        for (const out of ch.outfits) {
          const outfitImg = out.referenceImageUrl
          if (outfitImg) {
            items.push({
              uniqueKey: `OUTFIT_${out.id}`,
              name: `${ch.name} - ${out.lookName}`,
              subTitle: out.designDesc || '定制造型套系',
              imageUrl: outfitImg,
              sourceType: 'CHARACTER_REFERENCE',
              sourceId: out.id,
              characterId: ch.id,
              lookId: out.id,
              referenceRole: 'COMBINED'
            })
          }
        }
      }
    }

    // 3. 道具
    for (const p of pList || []) {
      if (p.coverUrl) {
        items.push({
          uniqueKey: `PROP_${p.id}`,
          name: p.name,
          subTitle: p.propType === 'KEY_PROP' ? '关键剧情道具' : '环境通用道具',
          imageUrl: p.coverUrl,
          sourceType: 'PROP',
          sourceId: p.id
        })
      }
    }

    allAssets.value = items
  } catch (err: any) {
    ElMessage.error(err?.message || '加载资产库素材失败')
  } finally {
    loading.value = false
  }
}

function open(options: {
  dramaId?: string | number
  maxSelectable: number
  alreadySelectedUrls?: string[]
  title?: string
}) {
  maxSelectable.value = Math.max(1, options.maxSelectable || 1)
  alreadySelectedUrls.value = options.alreadySelectedUrls || []
  if (options.title) {
    dialogTitle.value = options.title
  }
  selectedItems.value = []
  searchKeyword.value = ''
  activeCategory.value = 'ALL'
  visible.value = true

  loadAssets(options.dramaId)
}

function handleConfirm() {
  emit('confirm', [...selectedItems.value])
  visible.value = false
}

defineExpose({
  open
})
</script>
