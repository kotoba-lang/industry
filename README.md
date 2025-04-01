[ ] junonlyにアクセスできるのは認証済みのユーザーのみにする


[ ] People Table - 連絡先をくれた人のデータを保存する
[ ] Communication Table - 連絡先をくれた人との連絡情報を保存する
[ ] CommunicationChannel Table - 連絡先をくれた人との連絡情報を保存する
 
[ ] 回答した言葉の内容と音声は保存しません.

一連の@JungVoiceAssessment.tsx @index.tsx で利用する個々人のデータもDBに保存します。まず必要なスキーマを定義して、それぞれのコンポーネントで適切にデータが保存されるようにしてください。IPデータも保存してください。


[ ] darkモードでfooterの文字色が変わらない
[ ] contentlayerを使う
[ ] オリーブと鳩を散りばめる
[ ] 羊と狼, sheep, wolves

# A statically generated blog example using Next.js, Markdown, and TypeScript

This is the existing [blog-starter](https://github.com/vercel/next.js/tree/canary/examples/blog-starter) plus TypeScript.

This example showcases Next.js's [Static Generation](https://nextjs.org/docs/app/building-your-application/routing/layouts-and-templates) feature using Markdown files as the data source.

The blog posts are stored in `/_posts` as Markdown files with front matter support. Adding a new Markdown file in there will create a new blog post.

To create the blog posts we use [`remark`](https://github.com/remarkjs/remark) and [`remark-html`](https://github.com/remarkjs/remark-html) to convert the Markdown files into an HTML string, and then send it down as a prop to the page. The metadata of every post is handled by [`gray-matter`](https://github.com/jonschlinkert/gray-matter) and also sent in props to the page.

## Demo

[https://next-blog-starter.vercel.app/](https://next-blog-starter.vercel.app/)

## Deploy your own

Deploy the example using [Vercel](https://vercel.com?utm_source=github&utm_medium=readme&utm_campaign=next-example) or preview live with [StackBlitz](https://stackblitz.com/github/vercel/next.js/tree/canary/examples/blog-starter)

[![Deploy with Vercel](https://vercel.com/button)](https://vercel.com/new/clone?repository-url=https://github.com/vercel/next.js/tree/canary/examples/blog-starter&project-name=blog-starter&repository-name=blog-starter)

### Related examples

- [WordPress](/examples/cms-wordpress)
- [DatoCMS](/examples/cms-datocms)
- [Sanity](/examples/cms-sanity)
- [TakeShape](/examples/cms-takeshape)
- [Prismic](/examples/cms-prismic)
- [Contentful](/examples/cms-contentful)
- [Strapi](/examples/cms-strapi)
- [Agility CMS](/examples/cms-agilitycms)
- [Cosmic](/examples/cms-cosmic)
- [ButterCMS](/examples/cms-buttercms)
- [Storyblok](/examples/cms-storyblok)
- [GraphCMS](/examples/cms-graphcms)
- [Kontent](/examples/cms-kontent)
- [Umbraco Heartcore](/examples/cms-umbraco-heartcore)
- [Builder.io](/examples/cms-builder-io)
- [TinaCMS](/examples/cms-tina/)
- [Enterspeed](/examples/cms-enterspeed)

## How to use

Execute [`create-next-app`](https://github.com/vercel/next.js/tree/canary/packages/create-next-app) with [npm](https://docs.npmjs.com/cli/init), [Yarn](https://yarnpkg.com/lang/en/docs/cli/create/), or [pnpm](https://pnpm.io) to bootstrap the example:

```bash
npx create-next-app --example blog-starter blog-starter-app
```

```bash
yarn create next-app --example blog-starter blog-starter-app
```

```bash
pnpm create next-app --example blog-starter blog-starter-app
```

Your blog should be up and running on [http://localhost:3000](http://localhost:3000)! If it doesn't work, post on [GitHub discussions](https://github.com/vercel/next.js/discussions).

Deploy it to the cloud with [Vercel](https://vercel.com/new?utm_source=github&utm_medium=readme&utm_campaign=next-example) ([Documentation](https://nextjs.org/docs/deployment)).

# Notes

`blog-starter` uses [Tailwind CSS](https://tailwindcss.com) [(v3.0)](https://tailwindcss.com/blog/tailwindcss-v3).

# 環境構築と初期設定

## データベース設定

このプロジェクトは以下の構成でデータベース管理を行っています:

- **スキーマ定義とマイグレーション**: [Drizzle ORM](https://orm.drizzle.team)
- **データベースクライアント**: [Supabase](https://supabase.com)

### セットアップ手順

1. [Supabase](https://database.new) にアクセスし、新しいプロジェクトを作成します。
2. プロジェクト作成後、API キーを取得します。
3. `.env.local` ファイルを作成し、以下の環境変数を設定します：

```
NEXT_PUBLIC_SUPABASE_URL=https://your-project-id.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=your-anon-key
DATABASE_URL=postgres://postgres:[PASSWORD]@db.[YOUR-PROJECT-ID].supabase.co:5432/postgres
```

### マイグレーション管理

Drizzle を使用してデータベースのスキーマとマイグレーションを管理します：

```bash
# マイグレーションを生成
pnpm db:generate

# マイグレーションを適用
pnpm db:migrate

# 開発環境での直接スキーマ更新（本番環境では使用しないこと）
pnpm db:push

# データベースからスキーマを取得
pnpm db:pull

# Drizzle Studio でデータを確認・編集
pnpm db:studio
```

## 開発環境の起動

```bash
# 依存関係のインストール
pnpm install

# 開発サーバーの起動
pnpm dev
```

## テスト

```bash
# テストの実行
pnpm test
```

# プロジェクトについて

Spirit in Physics は、ユングの言語連想テストとAIを組み合わせた研究プロジェクトです。
[x] 曼荼羅を作成
[x] 研究同意書を作成
[x] Voice Assessmentが終わったら、Kawasaki Modelに反映する ( zustand )
[x] 研究協力アンケートを入れる  
