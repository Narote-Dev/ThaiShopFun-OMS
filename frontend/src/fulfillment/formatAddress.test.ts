import { describe, expect, it } from 'vitest'
import { formatPackAddress } from './formatAddress'

describe('formatPackAddress', () => {
  it('expands JSON address with province and postcode', () => {
    expect(
      formatPackAddress(
        JSON.stringify({
          line1: '99/1 ถ.สุขุมวิท',
          district: 'คลองเตย',
        }),
        'Bangkok',
        '10110',
      ),
    ).toEqual(['99/1 ถ.สุขุมวิท', 'คลองเตย', 'Bangkok 10110'])
  })

  it('falls back to plain text', () => {
    expect(formatPackAddress('ซอยตัวอย่าง 1', 'Bangkok', '10110')).toEqual([
      'ซอยตัวอย่าง 1',
      'Bangkok 10110',
    ])
  })
})
