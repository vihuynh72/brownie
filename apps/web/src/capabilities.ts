import { getCapabilities, type CapabilitiesResponse } from '@/api/client'

let cached: Promise<CapabilitiesResponse> | null = null

/** The deployment's limits, fetched once per page load and shared by every upload control. A failed fetch is retried on the next call. */
export function loadCapabilities(): Promise<CapabilitiesResponse> {
  if (!cached) {
    cached = getCapabilities().catch((error: unknown) => {
      cached = null
      throw error
    })
  }
  return cached
}

/** For tests: forget the cached answer. */
export function resetCapabilitiesCache(): void {
  cached = null
}

/** "10 MB", "512 KB", "900 bytes": the size a person reads, not the byte count the server enforces. */
export function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${trimZero(bytes / (1024 * 1024))} MB`
  if (bytes >= 1024) return `${trimZero(bytes / 1024)} KB`
  return `${bytes} bytes`
}

function trimZero(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}
