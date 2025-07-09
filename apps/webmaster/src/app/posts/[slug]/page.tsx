"use client";

import { Metadata } from "next";
import { notFound } from "next/navigation";
import { getAllPosts, getPostBySlug } from "@/lib/api";
import { CMS_NAME } from "@/lib/constants";
import markdownToHtml from "@/lib/markdownToHtml";
import Alert from "@/app/_components/alert";
import Container from "@/app/_components/container";
import Header from "@/app/_components/header";
import { PostBody } from "@/app/_components/post-body";
import { PostHeader } from "@/app/_components/post-header";
import PostContent from "./PostContent";
import { MDXRemote } from 'next-mdx-remote/rsc';
import { promises as fs } from 'fs';
import path from 'path';
import Image from "next/image";
import { InlineMath, BlockMath } from 'react-katex';
import 'katex/dist/katex.min.css';
import { JUNG_STIMULUS_WORDS } from "@/components/jung-word-assessment/JungWordTest";
import KawasakiModel from "@/components/kawasaki-model";
import SpiritInPhysicsInteractive from "@/components/spirit-in-physics/SpiritInPhysicsInteractive";

// MDX コンポーネントの定義
const mdxComponents = {
  Image,
  InlineMath,
  BlockMath,
  KawasakiModel,
  SpiritInPhysicsInteractive,
  // Jung stimulus words component
  JungWords: () => (
    <div className="flex flex-wrap gap-2 text-sm">
      {JUNG_STIMULUS_WORDS.join(", ")}
    </div>
  ),
};

// MDXファイルが存在するかチェックし、内容を読み込む
async function getMDXContent(slug: string) {
  try {
    const mdxPath = path.join(process.cwd(), '_posts', `${slug}.mdx`);
    const source = await fs.readFile(mdxPath, 'utf-8');
    
    // メタデータを抽出（export const meta = {...}; の部分）
    const metaMatch = source.match(/export const meta = ({[\s\S]*?});/);
    let meta = null;
    let content = source;
    
    if (metaMatch) {
      try {
        // メタデータを安全に抽出
        const metaString = metaMatch[1];
        meta = eval(`(${metaString})`);
        // import文とexport文を除去してコンテンツを取得
        content = source
          .replace(/^import.*$/gm, '')
          .replace(/export const meta = {[\s\S]*?};/, '')
          .trim();
      } catch (e) {
        console.warn('Failed to parse meta from MDX:', e);
      }
    }
    
    return { source: content, meta };
  } catch (error) {
    return null;
  }
}

export default async function Post(props: { params: Promise<{ slug: string }> }) {
  const params = await props.params;
  const { slug } = params;

  // まずMDXファイルの存在をチェック
  const mdxContent = await getMDXContent(slug);
  
  if (mdxContent) {
    // MDXファイルが存在する場合
    return (
      <main>
        <Container>
          <Header />
          <PostContent>
            <MDXRemote source={mdxContent.source} components={mdxComponents} />
          </PostContent>
        </Container>
      </main>
    );
  }

  // MDXファイルが存在しない場合は通常のMarkdown処理
  const post = getPostBySlug(slug);

  if (!post) {
    return notFound();
  }

  const content = await markdownToHtml(post.content || "");

  return (
    <main>
      <Alert preview={post.preview} />
      <Container>
        <Header />
        <article className="mb-32">
          <PostHeader
            title={post.title}
            coverImage={post.coverImage}
            date={post.date}
            author={post.author}
          />
          <PostBody content={content} />
        </article>
      </Container>
    </main>
  );
}

type Params = {
  params: Promise<{
    slug: string;
  }>;
};

export async function generateMetadata(props: Params): Promise<Metadata> {
  const params = await props.params;
  
  // MDXファイルからメタデータを取得を試行
  const mdxContent = await getMDXContent(params.slug);
  if (mdxContent?.meta) {
    const title = `${mdxContent.meta.title} | Next.js Blog Example with ${CMS_NAME}`;
    return {
      title,
      openGraph: {
        title,
        images: [mdxContent.meta.ogImage?.url || mdxContent.meta.coverImage],
      },
    };
  }
  
  // 通常のMarkdownファイルの処理
  const post = getPostBySlug(params.slug);

  if (!post) {
    return notFound();
  }

  const title = `${post.title} | Next.js Blog Example with ${CMS_NAME}`;

  return {
    title,
    openGraph: {
      title,
      images: [post.ogImage.url],
    },
  };
}

export async function generateStaticParams() {
  const posts = getAllPosts();

  return posts.map((post) => ({
    slug: post.slug,
  }));
}

