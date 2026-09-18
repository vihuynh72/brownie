<script setup lang="ts">
import { ICON_PATHS, type IconName } from './icons'

/**
 * Draws one icon from the shared set.
 *
 * Every icon here is decorative: it always sits beside a visible label,
 * or inside a control that carries its own accessible name, so the SVG is
 * hidden from assistive technology rather than given a title of its own --
 * a screen reader that announced both would say the same thing twice.
 */
const props = withDefaults(defineProps<{ name: IconName; size?: number }>(), { size: 20 })
</script>

<template>
  <svg
    class="app-icon"
    :width="props.size"
    :height="props.size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="d in ICON_PATHS[props.name]" :key="d" :d="d" />
  </svg>
</template>

<style scoped>
.app-icon {
  flex: none;
}
</style>
