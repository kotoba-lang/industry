import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Hume AI 音声生成ツール',
  description: '単語やフレーズをHume AIを使って音声に変換し、ローカルに保存するツールです',
};

export default function VoiceGeneratorLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <section>
      {children}
    </section>
  );
} 