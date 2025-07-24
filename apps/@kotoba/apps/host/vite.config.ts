import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// https://vitejs.dev/config/
export default defineConfig({
  server: {
    port: 5173,
    cors: true,
    hmr: {
      overlay: false
    }
  },
  plugins: [
    react()
  ],
  css: {
    postcss: './postcss.config.js'
  },
  resolve: {
    alias: {
      '@kotoba/components': path.resolve(__dirname, '../../kotoba/components')
    }
  },
  optimizeDeps: {
    include: [
      'cytoscape',
      'cytoscape-dagre',
      'prosemirror-state',
      'prosemirror-view',
      'prosemirror-model',
      'prosemirror-schema-basic',
      'prosemirror-schema-list',
      'prosemirror-example-setup'
    ]
  },
  build: {
    target: 'esnext'
  }
})
