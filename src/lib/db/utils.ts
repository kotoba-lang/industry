'use server'

import { db } from './index';
import { demographicData, consentRecords, NewDemographicData, NewConsentRecord } from './schema';
import { v4 as uuidv4 } from 'uuid';

/**
 * 同意フォームからの人口統計データと同意情報を保存
 */
export async function saveConsentAndDemographicData(
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
    // ユーザーIDを生成（セッションIDや認証済みユーザーIDがあればそれを使用）
    const userId = uuidv4();
    
    // 同意記録を保存
    const consentData: NewConsentRecord = {
      userId,
      consentGiven: consentInfo.consentGiven,
      consentVersion: consentInfo.consentVersion || '1.0',
      consentText: consentInfo.consentText,
      ipAddress: contextInfo?.ipAddress,
      userAgent: contextInfo?.userAgent,
      studyId: contextInfo?.studyId,
    };
    
    const [consentResult] = await db.insert(consentRecords).values(consentData).returning();
    
    // 同意が得られた場合のみ人口統計データを保存
    if (consentInfo.consentGiven) {
      const demographicRecord: NewDemographicData = {
        userId,
        ageGroup: demographicInfo.ageGroup || 'prefer-not-to-say',
        gender: demographicInfo.gender || 'prefer-not-to-say',
        ethnicity: demographicInfo.ethnicity || 'prefer-not-to-say',
        income: demographicInfo.income || 'prefer-not-to-say',
        ipAddress: contextInfo?.ipAddress,
        userAgent: contextInfo?.userAgent,
        studyId: contextInfo?.studyId,
        consentVersion: consentInfo.consentVersion || '1.0',
      };
      
      const [demographicResult] = await db.insert(demographicData).values(demographicRecord).returning();
      
      return {
        success: true,
        userId,
        consent: consentResult,
        demographic: demographicResult
      };
    }
    
    return {
      success: consentInfo.consentGiven,
      userId,
      consent: consentResult
    };
  } catch (error) {
    console.error('Error saving consent and demographic data:', error);
    throw error;
  }
} 