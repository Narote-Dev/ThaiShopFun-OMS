import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: {
    // Localhost only. #/admin/outbox calls /api through this proxy. No nginx change.
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      // Same-origin debugging only. T04 must not use this as the OIDC authority.
      '/tsf-idp': 'http://127.0.0.1:8090',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    exclude: ['e2e/**', 'node_modules/**', 'dist/**', 'playwright-report/**', 'test-results/**'],
  },
})
