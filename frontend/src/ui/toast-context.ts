import { createContext } from 'react'

export type ToastContextValue = {
  push: (text: string, variant?: 'success' | 'error') => void
}

export const ToastContext = createContext<ToastContextValue | null>(null)
