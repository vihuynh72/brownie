import { afterEach, describe, expect, it, vi } from 'vitest'
import { releaseIfStillHere } from '@/navigation'

function pageshow(persisted: boolean): Event {
  const event = new Event('pageshow')
  Object.defineProperty(event, 'persisted', { value: persisted })
  return event
}

describe('releaseIfStillHere', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('releases when the browser shows the page again from its history, and only once', () => {
    const release = vi.fn()
    releaseIfStillHere(release)

    window.dispatchEvent(pageshow(false))
    expect(release).not.toHaveBeenCalled()
    window.dispatchEvent(pageshow(true))
    window.dispatchEvent(pageshow(true))
    expect(release).toHaveBeenCalledTimes(1)
  })

  it('never releases on a timer, which cannot tell a page that stayed from one whose next page is still loading', () => {
    vi.useFakeTimers()
    const release = vi.fn()
    releaseIfStillHere(release)

    vi.advanceTimersByTime(60_000)
    expect(release).not.toHaveBeenCalled()
  })
})
