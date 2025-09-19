/** @type {import('next').NextConfig} */
const nextConfig = {
  experimental: {
    appDir: true,
  },
  webpack: (config, { isServer }) => {
    // Node.js ポリフィルの追加（InngestとKuzuで必要）
    if (!isServer) {
      config.resolve.fallback = {
        ...config.resolve.fallback,
        "async_hooks": false,
        "fs": false,
        "path": false,
        "crypto": false,
        "stream": false,
        "util": false,
        "url": false,
        "http": false,
        "https": false,
        "zlib": false,
        "querystring": false,
        "kuzu": false, // ブラウザでは使用しない
      };
    }

    // サーバーサイドでのみKuzuを有効にする
    if (isServer) {
      config.externals = config.externals || [];
      config.externals.push({
        'kuzu': 'commonjs kuzu'
      });
    }

    return config;
  },
  // サーバーサイドでのみKuzuを使用するための設定
  serverComponentsExternalPackages: ['kuzu'],
};

module.exports = nextConfig;
