<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <section class="page-column page-column--narrow not-found">
    <header class="page-head page-head--center">
      <h1 class="page-title page-title--small">There is nothing at this address</h1>
      <p class="page-lede not-found__lede">
        <code class="not-found__path">{{ route.path }}</code> is not a page in Brownie; the link may be mistyped, or
        what it pointed at may have been removed.
      </p>
    </header>
    <RouterLink class="button button--primary not-found__action" to="/">Go to your documents</RouterLink>
  </section>
</template>

<style scoped>
/* Starts a little way down the window, where the other pages' titles start, rather than hard against the top. */
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-block-start: clamp(var(--space-6), 14.25vh, 8.75rem);
}

.not-found__lede {
  max-inline-size: 36rem;
  margin: var(--space-4) auto 0;
}

/*
 * The address as a small white chip, in the interface's own typeface rather than a typewriter one. It can be long
 * and has no spaces to break at, so it may break anywhere rather than run off the page.
 */
.not-found__path {
  padding: 0.0625rem 0.375rem;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text);
  font-family: inherit;
  font-size: 0.9375em;
  overflow-wrap: anywhere;
}

.not-found__action {
  margin-block-start: var(--space-5);
}
</style>
