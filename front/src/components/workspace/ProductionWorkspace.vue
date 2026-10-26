<template>
  <section class="production-workspace">
    <WorkspaceHeader :title="title" :subtitle="subtitle" :icon="icon">
      <template #tag><slot name="tag" /></template>
      <template #stats><slot name="stats" /></template>
      <template #actions><slot name="actions" /></template>
    </WorkspaceHeader>
    <div v-if="$slots.toolbar" class="production-toolbar"><slot name="toolbar" /></div>
    <div class="production-workspace-body" :class="bodyClass"><slot /></div>
    <footer v-if="$slots.footer" class="production-footer"><slot name="footer" /></footer>
  </section>
</template>

<script setup lang="ts">
import type { Component } from 'vue'
import WorkspaceHeader from './WorkspaceHeader.vue'

defineProps<{ title: string; subtitle?: string; icon?: Component; bodyClass?: string }>()
</script>
