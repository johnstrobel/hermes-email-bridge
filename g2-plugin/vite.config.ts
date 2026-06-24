import { defineConfig } from 'vite'

export default defineConfig({
  server: {
    host: '0.0.0.0',
    port: 5199,
  },
  build: {
    target: 'es2022',
  },
  base: './',
})
