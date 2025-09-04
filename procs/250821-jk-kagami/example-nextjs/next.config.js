/** @type {import('next').NextConfig} */
const nextConfig = {
  transpilePackages: [
    'prosemirror-view',
    'prosemirror-state',
    'prosemirror-model',
    'prosemirror-schema-basic',
    'prosemirror-schema-list',
    'prosemirror-example-setup',
    'prosemirror-tables',
    'prosemirror-gapcursor',
    'hyperformula',
    'xlsx',
    'rxjs',
    'uuid'
  ],
  webpack: (config, { isServer }) => {
    // ブラウザー環境でのみNode.jsモジュールを無効化
    if (!isServer) {
      config.resolve.fallback = {
        ...config.resolve.fallback,
        fs: false,
        path: false,
        crypto: false,
      };
    }
    
    return config;
  },
}

module.exports = nextConfig; 