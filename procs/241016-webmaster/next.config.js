import createMDX from '@next/mdx';

/** @type {import('next').NextConfig} */
const nextConfig = {
  // MDXサポートを追加
  pageExtensions: ['js', 'jsx', 'mdx', 'ts', 'tsx'],
};

// MDXの設定
const withMDX = createMDX({
  // MDXプラグインを追加
  options: {
    remarkPlugins: [],
    rehypePlugins: [],
  },
});

export default withMDX(nextConfig);
