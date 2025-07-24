import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// https://vitejs.dev/config/
export default defineConfig({
  server: {
    port: 5173,
    cors: true
  },
  plugins: [
    react()
  ],
  resolve: {
    alias: {
      '@kotoba/components': path.resolve(__dirname, '../../kotoba/components')
    }
  },
  build: {
    target: 'esnext'
  }
})
