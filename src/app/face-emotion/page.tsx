import FaceEmotionAnalysis from "@/components/spirit-in-physics/FaceEmotionAnalysis";

export default function FaceEmotionPage() {
  return (
    <div className="container mx-auto px-4 py-8">
      <h1 className="text-3xl font-bold mb-6 text-center">
        リアルタイム感情分析
      </h1>
      <FaceEmotionAnalysis />
    </div>
  );
}
