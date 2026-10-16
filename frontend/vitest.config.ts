import { defineConfig } from 'vitest/config'

// Unit tests cover pure modules (reducer, engine with fakes, URL rules), so no DOM is needed.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    restoreMocks: true,
  },
})
