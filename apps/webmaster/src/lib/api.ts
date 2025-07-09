import { Post } from "@/interfaces/post";
import fs from "fs";
import { join } from "path";

const postsDirectory = join(process.cwd(), "_posts");

export function getPostSlugs() {
  const allFiles = fs.readdirSync(postsDirectory);
  return allFiles.filter((file) => file.endsWith(".mdx"));
}

export function getPostBySlug(slug: string): Post {
  const realSlug = slug.replace(/\.mdx$/, "");
  const fullPath = join(postsDirectory, `${realSlug}.mdx`);
  const fileContents = fs.readFileSync(fullPath, "utf8");
  
  // ES モジュールの meta export を解析
  const metaMatch = fileContents.match(/^export const meta = ({[\s\S]*?});/m);
  if (!metaMatch) {
    throw new Error(`No meta export found in ${realSlug}.mdx`);
  }
  
  const metaString = metaMatch[1];
  // 安全にメタデータを解析
  const meta = Function(`"use strict"; return (${metaString})`)();
  
  // content は meta export を除去してから取得
  const content = fileContents.replace(/^export const meta = {[\s\S]*?};[\s\S]*?\n/, '');
  
  return { ...meta, slug: realSlug, content } as Post;
}

export function getAllPosts(): Post[] {
  const slugs = getPostSlugs();
  const posts = slugs.map((slug) => {
    console.log(`Processing slug: ${slug}`);
    return getPostBySlug(slug);
  });
  
  // sort posts by date in descending order
  return posts.sort((post1, post2) => (post1.date > post2.date ? -1 : 1));
}
