import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { cn } from './cn'

type ToastMessage = { id: number; text: string; variant?: 'success' | 'error' }

type ToastContextValue = {
  push: (text: string, variant?: 'success' | 'error') => void
}

const ToastContext = createContext<ToastContextValue | null>(null)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastMessage[]>([])
  const push = useCallback((text: string, variant: 'success' | 'error' = 'success') => {
    const id = Date.now()
    setItems((prev) => [...prev, { id, text, variant }])
    window.setTimeout(() => {
      setItems((prev) => prev.filter((t) => t.id !== id))
    }, 4000)
  }, [])
  const value = useMemo(() => ({ push }), [push])
  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-[100] flex flex-col gap-2">
        {items.map((t) => (
          <div
            key={t.id}
            role="status"
            className={cn(
              'pointer-events-auto rounded-lg border px-4 py-2 text-[13px] shadow-card',
              t.variant === 'error'
                ? 'border-red-200 bg-red-50 text-red-900'
                : 'border-stone-200 bg-white text-stone-800',
            )}
          >
            {t.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast requires ToastProvider')
  return ctx
}
