<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <section class="card not-found">
    <h1>There is nothing at this address</h1>
    <p>
      <code>{{ route.path }}</code> is not a page in Brownie. The link may be mistyped, or it may point at something
      that has been removed.
    </p>
    <RouterLink class="button button--primary" to="/">Go to your documents</RouterLink>
  </section>
</template>

<style scoped>
.not-found {
  max-width: 32rem;
}

.not-found code {
  word-break: break-all;
}
</style>
