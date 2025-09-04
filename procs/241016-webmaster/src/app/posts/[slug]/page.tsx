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
import { MDXClientWrapper } from "./MDXClientWrapper";
import { promises as fs } from 'fs';
import path from 'path';

// MDXファイルのリスト
const mdxFiles = [
  '01', 'agent-noun', 'infomation-is-physical-quantity', 
  'love-is-self', 'sentiment-of-japanese', 'spirit-in-physics'
];

// MDXファイルからメタデータを抽出する関数
async function extractMDXMetadata(slug: string) {
  try {
    const mdxPath = path.join(process.cwd(), '_posts', `${slug}.mdx`);
    const source = await fs.readFile(mdxPath, 'utf-8');
    
    // export const meta = ({...}) の部分を抽出
    const metaMatch = source.match(/export const meta = ({[\s\S]*?});/);
    
    if (metaMatch) {
      try {
        // メタデータを安全に評価
        const metaString = metaMatch[1];
        // evalの代わりにFunctionコンストラクタを使用してより安全に評価
        const metaFunction = new Function('return ' + metaString);
        const meta = metaFunction();
        return meta;
      } catch (e) {
        console.warn('Failed to parse meta from MDX:', e);
        return null;
      }
    }
    
    return null;
  } catch (error) {
    return null;
  }
}

export default async function Post(props: { params: Promise<{ slug: string }> }) {
  const params = await props.params;
  const { slug } = params;

  // MDXファイルの処理
  if (mdxFiles.includes(slug)) {
    // MDXファイルからメタデータを取得
    const mdxMeta = await extractMDXMetadata(slug);
    
    return (
      <main>
        <Container>
          <Header />
          <article className="mb-32">
            {mdxMeta && (
              <PostHeader
                title={mdxMeta.title}
                coverImage={mdxMeta.coverImage}
                date={mdxMeta.date}
                author={mdxMeta.author}
              />
            )}
            <PostContent>
              <MDXClientWrapper slug={slug} />
            </PostContent>
          </article>
        </Container>
      </main>
    );
  }

  // 通常のMarkdownファイルの処理
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
  
  // MDXファイルの場合、動的にメタデータを取得
  if (mdxFiles.includes(params.slug)) {
    const mdxMeta = await extractMDXMetadata(params.slug);
    
    if (mdxMeta) {
      const title = `${mdxMeta.title} | Next.js Blog Example with ${CMS_NAME}`;
      return {
        title,
        openGraph: {
          title,
          images: [mdxMeta.ogImage?.url || mdxMeta.coverImage],
        },
      };
    }
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

