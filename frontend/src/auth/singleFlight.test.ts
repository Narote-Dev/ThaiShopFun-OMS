import { describe, expect, it } from 'vitest'
import { singleFlight } from './singleFlight'

describe('singleFlight', () => {
  it('runs overlapping calls once and a later call again', async () => {
    let current = 'r1'
    const seen: string[] = []
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const refresh = singleFlight(async () => {
      const presented = current
      seen.push(presented)
      await gate
      current = `${presented}-next`
      return 'access'
    })
    const first = Promise.all([refresh(), refresh()])
    await Promise.resolve()
    expect(seen).toEqual(['r1'])
    release()
    await first
    await refresh()
    expect(seen).toEqual(['r1', 'r1-next'])
  })
})
