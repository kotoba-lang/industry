import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import federation from '@originjs/vite-plugin-federation'

// https://vitejs.dev/config/
export default defineConfig({
  server: {
    cors: true
  },
  plugins: [
    react(),
    federation({
      name: 'host',
      remotes: {
        editor: `http://localhost:5001/remoteEntry.js`,
        graph: `http://localhost:5002/remoteEntry.js`,
      },
      shared: ['react', 'react-dom']
    })
  ],
  build: {
    modulePreload: false,
    target: 'esnext',
    minify: false,
    cssCodeSplit: false
  }
})
