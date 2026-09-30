import type { ReactNode } from 'react'

type Props = {
  signedIn: boolean
  fallback: ReactNode
  children: ReactNode
}

export default function RequireSession({ signedIn, fallback, children }: Props) {
  if (!signedIn) return fallback
  return children
}
