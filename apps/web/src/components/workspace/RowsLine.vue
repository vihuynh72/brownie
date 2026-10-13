<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'

/**
 * What stands where a table's rows go. While the table has none it is a line saying so, with the button that adds
 * the first row beside it; once there are rows it is just the button that adds another.
 */
defineProps<{
  /** The table has no rows yet. */
  empty: boolean
  /** Brownie found this table itself and the person has not yet said it is right. */
  found?: boolean
  /** A row is locked, so none can be added until it is unlocked. */
  locked: boolean
  /** Whether the line carries the button; where a page has several empty tables, only one does. */
  button?: boolean
}>()

defineEmits<{ add: [] }>()
</script>

<template>
  <span class="rows-line" :class="{ 'rows-line--empty': empty }">
    <template v-if="empty">
      <template v-if="found">
        <AppIcon name="sparkle" :size="12" class="rows-line__found" />
        <span class="visually-hidden">Found by Brownie. </span>
      </template>
      <span class="rows-line__text">No rows yet.</span>
    </template>
    <span v-if="button" class="rows-line__add">
      <button type="button" class="button button--secondary button--small" data-add-row :disabled="locked" @click="$emit('add')">
        <AppIcon name="plus" :size="16" />Add row
      </button>
      <span v-if="locked" class="field-hint">A row is locked, so rows cannot be added until it is unlocked.</span>
    </span>
  </span>
</template>

<style scoped>
.rows-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-3);
  /* The page's text around it may be italic; the button is not. */
  font-style: normal;
}

/* Standing in for rows, it is given room to be seen as a place of its own inside the table's frame. */
.rows-line--empty {
  padding-block: 0.375rem;
}

/* Under rows that are there already it is a line of its own, a little apart from the table above it. */
.rows-line:not(.rows-line--empty) {
  margin-block: 0.5rem 0.25rem;
}

/* The button is the page's own control, not the template's text, so it keeps the interface's typeface wherever it sits. */
.rows-line .button {
  font-family: 'Rubik', system-ui, sans-serif;
}

.rows-line__text {
  color: var(--color-text-muted);
  font-style: italic;
}

/* The sparkle sits close against the words, as it does against a found spot. */
.rows-line__found {
  margin-inline-end: -0.5rem;
  color: var(--color-honey-ink);
}

.rows-line__add {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-3);
}
</style>
