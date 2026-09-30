function formatExpiry(iso: string | null): string {
  if (!iso) return 'the renewal date'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toISOString().slice(0, 10)
}

export default function GraceBanner({ expiresAt }: { expiresAt: string | null }) {
  return (
    <p className="banner" role="status">
      This shop is read-only until {formatExpiry(expiresAt)}. Renew the membership to make changes.
    </p>
  )
}
