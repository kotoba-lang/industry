import JungWordAssessment from '@/components/jung-word-assessment/JungWordAssessment';

export const metadata = {
  title: "Jung's Word Association Test | 1910",
  description: "Take Carl Jung's original 1910 word association test to explore your subconscious mind",
};

export default function JungWordAssessmentPage() {
  return (
    <main className="container mx-auto px-4 py-12">
      <div className="max-w-4xl mx-auto">
        <div className="mb-8 text-center">
          <h1 className="text-4xl font-bold mb-4">Jung's Word Association Test (1910)</h1>
          <p className="text-xl text-gray-600">
            Explore your subconscious mind through word associations
          </p>
        </div>
        
        <div className="prose max-w-none mb-12">
          <h2>About the Test</h2>
          <p>
            Carl Jung published his "association method" in the American Journal of Psychology in 1910. 
            This test presents words one at a time and asks you to respond with the first word that 
            comes to mind.
          </p>
          <p>
            Jung theorized that delayed responses (taking longer than 2 seconds) might indicate 
            emotional "complexes" in the subconscious mind. He found that these complexes often 
            represented emotional conflicts or unresolved issues.
          </p>
          <h2>How It Works</h2>
          <ol>
            <li>You'll be presented with words one at a time</li>
            <li>Respond as quickly as possible with the first word that comes to mind</li>
            <li>Your response time will be measured</li>
            <li>After completing the test, you'll receive an analysis based on Jung's theories</li>
          </ol>
          <h2>Important Note</h2>
          <p>
            This test is provided for educational and entertainment purposes only. While Jung's theories 
            have been influential in psychology, modern psychological assessment is typically more 
            comprehensive.
          </p>
        </div>
        
        <div className="bg-white rounded-lg shadow-lg">
          <JungWordAssessment numberOfWords={30} />
        </div>
        
        <div className="mt-12 text-center text-sm text-gray-500">
          <p>
            Based on Carl Jung's original word association test from 1910. For educational purposes only.
          </p>
        </div>
      </div>
    </main>
  );
} 