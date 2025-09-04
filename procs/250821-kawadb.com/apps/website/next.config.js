/** @type {import('next').NextConfig} */
const nextConfig = {
  webpack: (config, { isServer }) => {
    // WASMファイルの処理を設定
    config.experiments = {
      ...config.experiments,
      asyncWebAssembly: true,
    }
    
    // .wasmファイルを直接処理
    config.module.rules.push({
      test: /\.wasm$/,
      type: 'webassembly/async',
    })
    
    // JavaScriptファイルの処理を設定
    config.module.rules.push({
      test: /\.js$/,
      include: /\/pkg\//,
      type: 'javascript/auto',
    })
    
    return config
  },
  // 静的ファイルの設定
  async headers() {
    return [
      {
        source: '/pkg/:path*',
        headers: [
          {
            key: 'Cross-Origin-Embedder-Policy',
            value: 'require-corp',
          },
          {
            key: 'Cross-Origin-Opener-Policy',
            value: 'same-origin',
          },
        ],
      },
    ]
  },
}

module.exports = nextConfig 