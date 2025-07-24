import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import federation from '@originjs/vite-plugin-federation'

// https://vitejs.dev/config/
export default defineConfig({
  server: {
    port: 5001,
    cors: true
  },
  plugins: [
    react(),
    federation({
      name: 'editor',
      filename: 'remoteEntry.js',
      exposes: {
        './Editor': './src/App.tsx',
      },
      shared: ['react', 'react-dom']
    })
  ],
  build: {
    target: 'esnext'
  }
})
