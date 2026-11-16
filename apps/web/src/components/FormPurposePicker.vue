<script setup lang="ts">
import AppIcon from './AppIcon.vue'
import type { IconName } from './icons'
import { FORM_PURPOSES, type FormPurposeId } from '@/landing/findForms'

defineProps<{ modelValue: FormPurposeId | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: FormPurposeId | null] }>()
const icons: Record<FormPurposeId, IconName> = {
  driving: 'car', work: 'briefcase', taxes: 'receipt', travel: 'airplane', health: 'heart', veterans: 'medal',
}
const compactLabels: Record<FormPurposeId, string> = {
  driving: 'Driving', work: 'Work', taxes: 'Taxes', travel: 'Travel', health: 'Medicare', veterans: 'Veterans',
}
</script>

<template>
  <div class="purpose-picker" role="group" aria-label="Browse forms by purpose">
    <button type="button" class="purpose-picker__option" :aria-pressed="modelValue === null" @click="emit('update:modelValue', null)">All forms</button>
    <button v-for="purpose in FORM_PURPOSES" :key="purpose.id" type="button" class="purpose-picker__option"
      :aria-label="purpose.label" :data-short-label="compactLabels[purpose.id]"
      :aria-pressed="modelValue === purpose.id" @click="emit('update:modelValue', purpose.id)">
      <AppIcon :name="icons[purpose.id]" :size="18" /><span class="purpose-picker__label">{{ purpose.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.purpose-picker { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.purpose-picker__option {
  display: inline-flex; align-items: center; justify-content: center; gap: var(--space-2);
  min-block-size: 2.75rem; padding: var(--space-2) var(--space-3); border: 1px solid var(--color-outline);
  border-radius: var(--radius-control); background: var(--color-surface); color: var(--color-text);
  font-size: var(--font-size-sm); line-height: 1.25; white-space: nowrap; cursor: pointer;
  transition: background-color var(--motion-fast) var(--motion-ease), transform var(--motion-fast) var(--motion-ease);
}
.purpose-picker__option:hover { background: var(--color-paper-deep); }
.purpose-picker__option[aria-pressed='true'] { background: var(--color-cocoa); border-color: var(--color-cocoa); color: var(--color-surface); }
.purpose-picker__option:active { transform: translateY(1px); }
@media (max-width: 35rem) {
  .purpose-picker { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .purpose-picker__label { display: none; }
  .purpose-picker__option[data-short-label]::after { content: attr(data-short-label); }
}
</style>
