<template>
  <span class="brand">
    <img class="brand__mark" src="/art/brownie-mascot-v2.webp" width="40" height="40" alt="" />
    <span>Brownie</span>
  </span>
</template>

<style scoped>
.brand {
  display: inline-flex;
  align-items: center;
  gap: 0.625rem;
  font-family: var(--font-ui);
  font-size: 1.125rem;
  font-weight: 600;
  letter-spacing: -0.025em;
  line-height: 1;
}

.brand__mark {
  inline-size: 2.5rem;
  block-size: 2.5rem;
  object-fit: contain;
}
</style>
