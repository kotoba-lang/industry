interface ResearchConsentFormProps {
  onConsent: () => void;
  onDecline: () => void;
}

export default function ResearchConsentForm({ onConsent, onDecline }: ResearchConsentFormProps) {
  return (
    <div className="bg-blue-50 p-3 sm:p-6 md:p-8 rounded-lg max-w-xl mx-auto my-4">
      <h2 className="text-2xl sm:text-3xl font-bold mb-4 text-center">
        Research Participation Consent
      </h2>
      
      <div className="text-sm sm:text-base space-y-4">
        <p>
          This Spirit in Physics (Jung's Word Association Embedding Test) is conducted for research purposes.
          Please read the following information carefully before proceeding.
        </p>
        
        {/* 同意内容の詳細 */}
        {/* ... */}
        
        <div className="flex flex-col sm:flex-row gap-3 mt-6">
          <button 
            className="bg-blue-600 text-white py-2 px-4 rounded hover:bg-blue-700 transition-colors"
            onClick={onConsent}
          >
            I Consent
          </button>
          <button 
            className="bg-gray-200 text-gray-800 py-2 px-4 rounded hover:bg-gray-300 transition-colors"
            onClick={onDecline}
          >
            I Decline
          </button>
        </div>
      </div>
    </div>
  );
} 