'use server'

import { saveConsentAndDemographicData } from '@/lib/db/utils';

// Server action for saving consent data
export async function saveConsentData(
  demographicInfo: {
    ageGroup: string;
    gender: string;
    ethnicity: string;
    income: string;
  },
  consentInfo: {
    consentGiven: boolean;
    consentVersion?: string;
    consentText?: string;
  },
  contextInfo?: {
    ipAddress?: string;
    userAgent?: string;
    studyId?: string;
  }
) {
  try {
    return await saveConsentAndDemographicData(
      demographicInfo,
      consentInfo,
      contextInfo
    );
  } catch (error) {
    console.error('Error in server action saveConsentData:', error);
    return { success: false, error: 'Failed to save consent data' };
  }
} 