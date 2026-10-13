<script setup lang="ts">
import { computed } from 'vue'
import { ICONS, type IconName } from './icons'

/**
 * Draws one icon from the shared set.
 *
 * Every icon here is decorative: it always sits beside a visible label,
 * or inside a control that carries its own accessible name, so the SVG is
 * hidden from assistive technology rather than given a title of its own --
 * a screen reader that announced both would say the same thing twice.
 *
 * The colour is always `currentColor`, so an icon follows the text beside it.
 * What differs is how the shapes are painted. An outline icon already has its
 * line weight built into the shape, so it is filled and given no stroke (a
 * stroke on top would thicken it); a stroke icon is centre lines, so it is
 * stroked and left unfilled. These are plain attributes rather than CSS so the
 * markup draws the same wherever it ends up.
 */
const props = withDefaults(defineProps<{ name: IconName; size?: number }>(), { size: 20 })

const STROKED = {
  fill: 'none',
  stroke: 'currentColor',
  'stroke-width': 1.7,
  'stroke-linecap': 'round',
  'stroke-linejoin': 'round',
} as const
const FILLED = { fill: 'currentColor' } as const

const art = computed(() => ICONS[props.name])
const painting = computed(() => (art.value.kind === 'stroke' ? STROKED : FILLED))
</script>

<template>
  <svg
    class="app-icon"
    :width="props.size"
    :height="props.size"
    viewBox="0 0 24 24"
    v-bind="painting"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="(d, index) in art.paths" :key="index" :d="d" />
  </svg>
</template>

<style scoped>
.app-icon {
  flex: none;
}
</style>
