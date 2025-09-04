'use client';

import { useRouter } from 'next/navigation';
import ConsentForm from '@/components/consent/ConsentForm';
import { DemographicData } from '@/lib/db/schema/demographic';

export default function ConsentPage() {
  const router = useRouter();

  const handleConsent = (demographicData: DemographicData) => {
    console.log('User consented with demographic data:', demographicData);
    
    // 同意後にテスト画面へ移動
    router.push('/test');
  };

  return (
    <main className="min-h-screen p-4 bg-gray-50">
      <div className="container mx-auto py-8">
        <ConsentForm
          onConsent={handleConsent}
          consentVersion="1.0"
          studyId="SPIRIT-IN-PHYSICS-2025"
        />
      </div>
    </main>
  );
} 