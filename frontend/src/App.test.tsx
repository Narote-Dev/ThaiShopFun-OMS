import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App.tsx'

vi.mock('./auth/session', () => ({
  bootSession: vi.fn(async () => ({ kind: 'anonymous' as const, error: null })),
  getAccessToken: () => null,
  logout: vi.fn(async () => undefined),
  refreshAccessToken: vi.fn(),
  resumeLogin: vi.fn(),
  setSessionListener: vi.fn(),
  startLogin: vi.fn(async () => undefined),
}))

describe('App', () => {
  it('shows sign in when boot stays on the manual screen', async () => {
    render(<App />)
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'OMS' })).toBeInTheDocument()
  })
})
