<script setup lang="ts">
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'

/**
 * The trash bin, which is empty for a reason worth saying out loud rather
 * than leaving as a blank page: a Brownie workspace keeps every document
 * and every uploaded file it has been given, and nothing in the product
 * moves anything here. Removing a document, and the deletion that has to
 * follow it through derived files, previews and exports, is real work this
 * page is waiting on; until it exists, this says so plainly instead of
 * showing an empty list that looks like a load that failed.
 */
</script>

<template>
  <section class="trash">
    <span class="trash__mark" aria-hidden="true"><AppIcon name="trash" :size="28" /></span>
    <h1 class="trash__title">Your trash bin is empty</h1>
    <p>
      Every document and uploaded file in your workspace stays in your workspace: Brownie does not move anything here,
      and nothing you can do in the app removes a document.
    </p>
    <p class="field-hint">
      If you need something gone, ask whoever runs this Brownie to remove it for you, and say which document you mean.
    </p>
    <RouterLink class="button button--secondary" to="/">Back to your documents</RouterLink>
  </section>
</template>

<style scoped>
.trash {
  max-inline-size: 34rem;
  margin-inline: auto;
  margin-block-start: var(--space-6);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-3);
}

.trash__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  inline-size: 3rem;
  block-size: 3rem;
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
  color: var(--color-cocoa-strong);
}

.trash__title {
  margin: 0;
  font-size: var(--font-size-xl);
}
</style>
