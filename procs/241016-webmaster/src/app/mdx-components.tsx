import type { MDXComponents } from 'mdx/types';
import Image from 'next/image';

/**
 * MDXファイルで使用するカスタムコンポーネントを定義
 */
export function useMDXComponents(components: MDXComponents): MDXComponents {
  return {
    // カスタムコンポーネントをここに追加
    h1: (props: React.ComponentProps<'h1'>) => (
      <h1 className="text-4xl md:text-5xl font-bold tracking-tight mb-6 text-gray-900 dark:text-gray-100" {...props} />
    ),
    h2: (props: React.ComponentProps<'h2'>) => (
      <h2 className="text-3xl md:text-4xl font-bold tracking-tight mt-8 mb-4 text-gray-900 dark:text-gray-100" {...props} />
    ),
    h3: (props: React.ComponentProps<'h3'>) => (
      <h3 className="text-2xl md:text-3xl font-bold tracking-tight mt-6 mb-3 text-gray-900 dark:text-gray-100" {...props} />
    ),
    h4: (props: React.ComponentProps<'h4'>) => (
      <h4 className="text-xl md:text-2xl font-semibold mt-6 mb-2 text-gray-900 dark:text-gray-100" {...props} />
    ),
    p: (props: React.ComponentProps<'p'>) => (
      <p className="text-lg leading-relaxed mb-6 text-gray-700 dark:text-gray-300" {...props} />
    ),
    ul: (props: React.ComponentProps<'ul'>) => (
      <ul className="list-disc list-inside mb-6 space-y-2 text-gray-700 dark:text-gray-300" {...props} />
    ),
    ol: (props: React.ComponentProps<'ol'>) => (
      <ol className="list-decimal list-inside mb-6 space-y-2 text-gray-700 dark:text-gray-300" {...props} />
    ),
    li: (props: React.ComponentProps<'li'>) => (
      <li className="text-lg leading-relaxed" {...props} />
    ),
    code: (props: React.ComponentProps<'code'>) => (
      <code className="bg-gray-100 dark:bg-gray-800 px-2 py-1 rounded font-mono text-sm text-red-600 dark:text-red-400" {...props} />
    ),
    pre: (props: React.ComponentProps<'pre'>) => (
      <pre className="bg-gray-100 dark:bg-gray-800 p-4 rounded-lg mb-6 overflow-x-auto border" {...props} />
    ),
    blockquote: (props: React.ComponentProps<'blockquote'>) => (
      <blockquote className="border-l-4 border-blue-500 pl-6 italic mb-6 text-gray-600 dark:text-gray-400" {...props} />
    ),
    img: (props: React.ComponentProps<'img'>) => (
      <div className="my-8 text-center">
        <img className="mx-auto max-w-full h-auto rounded-lg shadow-md" {...props} />
      </div>
    ),
    hr: (props: React.ComponentProps<'hr'>) => (
      <hr className="my-8 border-gray-300 dark:border-gray-600" {...props} />
    ),
    a: (props: React.ComponentProps<'a'>) => (
      <a className="text-blue-600 dark:text-blue-400 hover:underline font-medium" {...props} />
    ),
    strong: (props: React.ComponentProps<'strong'>) => (
      <strong className="font-bold text-gray-900 dark:text-gray-100" {...props} />
    ),
    em: (props: React.ComponentProps<'em'>) => (
      <em className="italic text-gray-700 dark:text-gray-300" {...props} />
    ),
    // ここにカスタムコンポーネントを追加可能
    ...components,
  };
} 