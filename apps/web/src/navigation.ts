/**
 * The one place this app performs a full-page navigation of its own (as
 * opposed to a router push): finishing sign-out at the identity provider.
 * Kept behind a function so a component test can observe the destination
 * instead of jsdom's unimplemented window.location.assign.
 */
export function navigateTo(url: string): void {
  window.location.assign(url)
}
