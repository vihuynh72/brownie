<template>
  <span class="brand">
    <img class="brand__mark" src="/art/brownie-mark-v3.webp" width="32" height="32" alt="" />
    <span>Brownie</span>
  </span>
</template>

<style scoped>
.brand {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-family: var(--font-ui);
  font-size: 1.125rem;
  font-weight: 600;
  letter-spacing: -0.025em;
  line-height: 1;
}

.brand__mark {
  inline-size: 2rem;
  block-size: 2rem;
  object-fit: contain;
}
</style>
