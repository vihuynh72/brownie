/**
 * The places this app performs a full-page navigation of its own (as
 * opposed to a router push): finishing sign-out at the identity provider,
 * and going to Google's page to connect an account. Kept behind a function
 * so a component test can observe the destination instead of jsdom's
 * unimplemented window.location.assign.
 */
export function navigateTo(url: string): void {
  window.location.assign(url)
}

/**
 * For a button that stays disabled while its page leaves: calls {@code release}
 * when the browser shows this page again from its history (Back from Google's
 * page), so the button is not left dead. Deliberately not on a timer: a timer
 * cannot tell a page that stayed from one whose next page is still loading, and
 * pressing again then would replace the consent the first press started.
 */
export function releaseIfStillHere(release: () => void): void {
  const onShow = (event: PageTransitionEvent) => {
    if (event.persisted) {
      window.removeEventListener('pageshow', onShow)
      release()
    }
  }
  window.addEventListener('pageshow', onShow)
}
