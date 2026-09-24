<template>
  <el-config-provider :locale="zhCn" :size="elementSize">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useAppearance } from '@/composables/useAppearance'

const { density } = useAppearance()

const elementSize = computed<'small' | 'default'>(() => {
  return density.value === 'compact' ? 'small' : 'default'
})
</script>
