"use client";

import { Suspense } from "react";
import dynamic from 'next/dynamic';

interface MDXClientWrapperProps {
  slug: string;
}

export function MDXClientWrapper({ slug }: MDXClientWrapperProps) {
  const MDXComponent = dynamic(() => import(`../../../../_posts/${slug}.mdx`), {
    loading: () => <div className="p-4">Loading...</div>,
    ssr: false
  });

  return (
    <Suspense fallback={<div className="p-4">Loading...</div>}>
      <MDXComponent />
    </Suspense>
  );
} 