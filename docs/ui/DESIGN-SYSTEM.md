# ThaiShopFun OMS — UI design system

Frontend pages use **Tailwind CSS** (npm), **lucide-react**, and shared components under `frontend/src/ui/`.

## Tokens

| Token | Value |
|-------|--------|
| Brand coral | `brand-600` `#dc4516` (scale `brand-50`–`900`) |
| Page background | `canvas` `#f7f5f2` |
| Neutrals | Tailwind `stone` |
| Cards | `rounded-xl`, `border-stone-200`, `shadow-card` |
| Fonts | IBM Plex Sans Thai (UI), IBM Plex Mono (IDs/SKUs) via `@fontsource` |

## Status colours

Central mapping: `frontend/src/ui/status.ts` and `StatusBadge`. Examples:

- `READY_TO_PICK` → green
- `ON HOLD` / `OUT_OF_STOCK` → red
- `SKU_NOT_MAPPED` → amber
- `UNFULFILLED` / `CANCELLED` → muted (cancelled strikethrough)
- `COD` → violet; `PREPAID` → blue
- Channel `TSF` → coral

## Layout

- **AppShell** — app shell: full-viewport column layout (`min-h-dvh`). On **lg+**, the sidebar is `sticky top-0 h-dvh` in the flex row (nav scrolls inside the aside; user footer stays at the bottom). On **narrow** viewports it stays a fixed off-canvas drawer (`max-lg:fixed`) with a slide transform only on mobile (transform is not applied on lg, so sticky height is reliable). Main column is `flex-1 min-h-dvh`; page body scrolls with the document while the top bar stays `sticky`.
- **PageContent** — max width + padding (`wide` for tables)
- **PageHeader** — title, subtitle, actions

## Rule for new pages

New screens must use **AppShell** (via signed-in layout), **PageHeader**, and `ui/*` components. No ad-hoc global CSS or unstyled HTML tables.

## Components

`Button`, `Badge`, `StatusBadge`, `Card`, `KpiCard`, `DataTable`, `AlertBanner`, `Input`, `Label`, `Select`, `PageContent`, `PageHeader`, `EmptyState`, `TablePager`.
