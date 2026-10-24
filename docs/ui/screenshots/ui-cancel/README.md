# Cancel-request UI screenshots

These four Chromium captures render this branch's real pages and shared UI components with local, deterministic frontend API fixtures (24 October 2026). They illustrate the UI; they are not evidence of a live TSF delivery. No backend files were changed.

- [Cancel dialog](cancel-request-dialog.png)
- [Pending banner with the request time](cancel-pending-banner.png)
- [Fifth tab: รอยกเลิก, with count](orders-cancel-pending-tab.png)
- [Stock post confirmation](stock-post-dialog.png)

The browser check exercised Tab and Shift+Tab focus trapping, Escape dismissal, cancel submission followed by the pending banner, and dismissal of stock posting without changing the draft. No browser errors occurred.

To capture these states against the localhost demo stack instead, seed the demo catalog/orders and run `node scripts/screenshot-pages.mjs` from `frontend`. The helper submits a demo cancel request and creates a stock draft to capture its post dialog; it dismisses that dialog without posting stock.
