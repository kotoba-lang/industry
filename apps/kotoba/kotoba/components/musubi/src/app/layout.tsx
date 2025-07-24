import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "数学理論生物体系図 - 編集可能版",
  description: "数学理論の相互関係を生物学的メタファーで可視化し、編集可能なインタラクティブな図表",
  keywords: "数学, 理論, 可視化, 生物学, Cytoscape, インタラクティブ, 編集",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ja">
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
      </head>
      <body className="antialiased">
        {children}
      </body>
    </html>
  );
}
