'use client';

import { notFound } from "next/navigation";
import Container from "@/app/_components/container";
import Header from "@/app/_components/header";
import PostContent from "./PostContent";
import { Suspense } from "react";
import dynamic from 'next/dynamic';
import React from "react";

// 動的インポート用の関数
const dynamicImport = (slug: string) => {
  switch (slug) {
    case '01':
      return dynamic(() => import('../../../../_posts/01.mdx'), {
        loading: () => <div>Loading...</div>,
        ssr: true
      });
    case 'agent-noun':
      return dynamic(() => import('../../../../_posts/agent-noun.mdx'), {
        loading: () => <div>Loading...</div>,
        ssr: true
      });
    case 'infomation-is-physical-quantity':
      return dynamic(() => import('../../../../_posts/infomation-is-physical-quantity.mdx'), {
        loading: () => <div>Loading...</div>,
        ssr: true
      });
    case 'love-is-self':
      return dynamic(() => import('../../../../_posts/love-is-self.mdx'), {
        loading: () => <div>Loading...</div>,
        ssr: true
      });
    case 'sentiment-of-japanese':
      return dynamic(() => import('../../../../_posts/sentiment-of-japanese.mdx'), {
        loading: () => <div>Loading...</div>,
        ssr: true
      });
    case 'spirit-in-physics':
      return dynamic(() => import('../../../../_posts/spirit-in-physics.mdx'), {
        loading: () => <div>Loading...</div>,
        ssr: true
      });
    default:
      return null;
  }
};

type Params = {
  params: Promise<{
    slug: string;
  }>;
};

export default function Post(props: Params) {
  const params = React.use(props.params);
  const { slug } = params;

  const MDXComponent = dynamicImport(slug);

  if (!MDXComponent) {
    return notFound();
  }

  return (
    <main>
      <Container>
        <Header />
        <PostContent>
          <Suspense fallback={<div>Loading...</div>}>
            <MDXComponent />
          </Suspense>
        </PostContent>
      </Container>
    </main>
  );
}

