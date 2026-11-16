<template>
  <span class="brand">
    <svg class="brand__mark" viewBox="0 0 28 28" aria-hidden="true" focusable="false">
      <rect x="1" y="1" width="26" height="26" rx="8" fill="currentColor" />
      <path d="M10 7.5h4.5c2.2 0 3.5 1 3.5 2.8 0 1.4-.8 2.3-2 2.6 1.6.3 2.5 1.3 2.5 2.9 0 2-1.5 3.2-3.9 3.2H10V7.5Zm2.3 2v2.6h2c.9 0 1.4-.5 1.4-1.3s-.5-1.3-1.4-1.3h-2Zm0 4.5v3h2.2c1.1 0 1.7-.5 1.7-1.5s-.6-1.5-1.7-1.5h-2.2Z" fill="var(--color-surface)" />
    </svg>
    <span>Brownie</span>
  </span>
</template>

<style scoped>
.brand {
  display: inline-flex;
  align-items: center;
  gap: 0.625rem;
  font-family: var(--font-ui);
  font-size: 1rem;
  font-weight: 600;
  letter-spacing: -0.025em;
  line-height: 1;
}

.brand__mark {
  inline-size: 1.75rem;
  block-size: 1.75rem;
  color: var(--color-cocoa-strong);
}
</style>
