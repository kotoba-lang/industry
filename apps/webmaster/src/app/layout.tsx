import Footer from "@/app/_components/footer";
import { CMS_NAME, HOME_OG_IMAGE_URL } from "@/lib/constants";
import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";
import cn from "classnames";
import { ThemeSwitcher } from "./_components/theme-switcher";

import "./globals.css";

const inter = Inter({ subsets: ["latin"] });

export const viewport: Viewport = {
  themeColor: '#000',
};

export const metadata: Metadata = {
  title: `Jun Kawasaki - ${CMS_NAME}`,
  description: `Proverbs 1:7 The fear of Jehovah is the beginning of knowledge; But the foolish despise wisdom and instruction.`,
  openGraph: {
    images: [HOME_OG_IMAGE_URL],
  },
  icons: {
    icon: [
      { url: '/favicon/favicon-32x32.png', sizes: '32x32', type: 'image/png' },
      { url: '/favicon/favicon-16x16.png', sizes: '16x16', type: 'image/png' },
    ],
    shortcut: '/favicon/favicon.ico',
    apple: {
      url: '/favicon/apple-touch-icon.png',
      sizes: '180x180',
    },
    other: [
      {
        rel: 'mask-icon',
        url: '/favicon/safari-pinned-tab.svg',
        color: '#000000',
      },
    ],
  },
  manifest: '/favicon/site.webmanifest',
  metadataBase: new URL('https://junkawasaki.com'),
  alternates: {
    types: {
      'application/rss+xml': '/feed.xml',
    },
  },
  other: {
    'msapplication-TileColor': '#000000',
    'msapplication-config': '/favicon/browserconfig.xml',
    'copyright': 'cc-by-nd-sa',
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" suppressHydrationWarning>
      {/* Next.js App Router automatically handles head elements from metadata */}
      <body
        className={cn(inter.className, "dark:bg-slate-900 dark:text-slate-400")}
      >
        <ThemeSwitcher />
        <div className="min-h-screen">{children}</div>
        <Footer />
      </body>
    </html>
  );
}
