'use client';

import { ReactNode } from 'react';

interface PostContentProps {
  children: ReactNode;
}

export default function PostContent({ children }: PostContentProps) {
  return (
    <div className="max-w-4xl mx-auto">
      <article className="prose prose-xl lg:prose-2xl max-w-none 
        prose-headings:text-gray-900 dark:prose-headings:text-gray-100
        prose-p:text-gray-800 dark:prose-p:text-gray-200
        prose-li:text-gray-800 dark:prose-li:text-gray-200
        prose-strong:text-gray-900 dark:prose-strong:text-gray-100
        prose-a:text-blue-600 dark:prose-a:text-blue-400
        prose-blockquote:text-gray-700 dark:prose-blockquote:text-gray-300
        prose-code:text-gray-900 dark:prose-code:text-gray-100
        prose-pre:bg-gray-100 dark:prose-pre:bg-gray-800
        prose-pre:text-gray-900 dark:prose-pre:text-gray-100
        prose-th:text-gray-900 dark:prose-th:text-gray-100
        prose-td:text-gray-800 dark:prose-td:text-gray-200
        prose-lead:text-gray-700 dark:prose-lead:text-gray-300
        prose-h1:text-4xl prose-h1:font-bold prose-h1:mb-6 prose-h1:mt-8
        prose-h2:text-3xl prose-h2:font-semibold prose-h2:mb-4 prose-h2:mt-6
        prose-h3:text-2xl prose-h3:font-medium prose-h3:mb-3 prose-h3:mt-4
        prose-h4:text-xl prose-h4:font-medium prose-h4:mb-2 prose-h4:mt-3
        prose-h5:text-lg prose-h5:font-medium prose-h5:mb-2 prose-h5:mt-2
        prose-h6:text-base prose-h6:font-medium prose-h6:mb-1 prose-h6:mt-2
        prose-p:mb-4 prose-p:leading-relaxed
        prose-ul:my-4 prose-ol:my-4
        prose-li:my-1
        prose-blockquote:border-l-4 prose-blockquote:border-gray-300 dark:prose-blockquote:border-gray-600
        prose-blockquote:pl-4 prose-blockquote:py-2 prose-blockquote:my-4
        prose-blockquote:bg-gray-50 dark:prose-blockquote:bg-gray-800/50
        prose-hr:border-gray-300 dark:prose-hr:border-gray-600
        prose-table:w-full prose-table:my-4
        prose-thead:bg-gray-50 dark:prose-thead:bg-gray-800
        prose-tr:border-b prose-tr:border-gray-200 dark:prose-tr:border-gray-700
        prose-img:mx-auto prose-img:rounded-lg prose-img:shadow-lg prose-img:my-6
        prose-figcaption:text-center prose-figcaption:text-gray-600 dark:prose-figcaption:text-gray-400
        prose-figcaption:mt-2 prose-figcaption:text-sm
        prose-video:mx-auto prose-video:rounded-lg prose-video:shadow-lg prose-video:my-6">
        {children}
      </article>
    </div>
  );
} 