import FaceEmotionBatchAnalysis from '@/components/spirit-in-physics/FaceEmotionBatchAnalysis';

export const metadata = {
  title: 'バッチ式感情分析',
  description: 'Hume AIを使った録画式のバッチ感情分析',
};


export default function FaceEmotionBatchPage() {
  return (
    <div className="container mx-auto py-8">
      <FaceEmotionBatchAnalysis />
    </div>
  );
}
