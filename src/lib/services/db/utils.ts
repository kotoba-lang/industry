'use server'

import { createSupabaseServerClient } from '@/lib/supabase/server';

/**
 * 同意データと人口統計データを保存する関数
 * @param demographicInfo 人口統計情報
 * @param consentInfo 同意情報
 * @param contextInfo コンテキスト情報
 * @returns 操作結果
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
    const supabase = await createSupabaseServerClient();
    
    // ユーザーIDを生成（本番では認証システムからユーザーIDを取得）
    const userId = `user_${Date.now()}_${Math.floor(Math.random() * 1000)}`;
    
    // 同意データの挿入
    const { error: consentError } = await supabase
      .from('consents')
      .insert({
        user_id: userId,
        consent_given: consentInfo.consentGiven,
        consent_version: consentInfo.consentVersion,
        consent_text: consentInfo.consentText,
        ip_address: contextInfo?.ipAddress,
        user_agent: contextInfo?.userAgent,
        study_id: contextInfo?.studyId,
        timestamp: new Date().toISOString()
      });
    
    if (consentError) {
      console.error('Error saving consent data:', consentError);
      return { success: false, error: consentError.message };
    }
    
    // 人口統計データの挿入
    const { error: demographicError } = await supabase
      .from('demographics')
      .insert({
        user_id: userId,
        age_group: demographicInfo.ageGroup,
        gender: demographicInfo.gender,
        ethnicity: demographicInfo.ethnicity,
        income: demographicInfo.income,
        timestamp: new Date().toISOString()
      });
    
    if (demographicError) {
      console.error('Error saving demographic data:', demographicError);
      return { success: false, error: demographicError.message };
    }
    
    return { success: true, userId };
  } catch (error) {
    console.error('Error in saveConsentAndDemographicData:', error);
    return { success: false, error: 'Failed to save data' };
  }
} 