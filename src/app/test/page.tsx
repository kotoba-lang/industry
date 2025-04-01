"use client";

import { JungVoiceAssessment } from '@/components/jung-voice-assessment';

export default function TestPage() {
  return (
    <div className="container mx-auto py-8 px-4">
      <h1 className="text-3xl font-bold text-center mb-8">Jung's Word Association Test (AI Voice Guide)</h1>
      
      <div className="max-w-4xl mx-auto">
        <JungVoiceAssessment 
          numberOfWords={3} // Setting a low word count for testing
          speechRecognitionLang="en-US" // Changed to English
        />
      </div>
      
      <div className="mt-8 p-4 bg-gray-50 rounded-lg max-w-4xl mx-auto">
        <h2 className="text-xl font-semibold mb-4">About This Test</h2>
        <p className="mb-2">
          This test is based on Carl Gustav Jung's word association test with AI voice guidance.
          For each word presented, please respond verbally with the first word that comes to mind.
        </p>
        <p className="mb-2">
          By analyzing response times and patterns, this test can help identify unconscious complexes.
          Words with delayed responses (more than 2 seconds) may indicate potentially significant psychological reactions.
        </p>
        <p>
          <strong>Note:</strong> This test requires microphone access permission. Voice data is processed locally in your browser
          and is not transmitted externally.
        </p>
      </div>
    </div>
  );
} 