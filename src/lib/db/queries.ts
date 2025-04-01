'use server'

import { createServerClient } from '@/lib/supabase/server'

// Define types for the data
interface ConsentRecord {
  id: string
  user_id: string
  created_at: string
  consent_given: boolean
  consent_version: string
  consent_text?: string
  ip_address?: string
  user_agent?: string
  study_id?: string
  researcher_note?: string
}

interface DemographicData {
  id: string
  user_id: string
  created_at: string
  age_group: string
  gender: string
  ethnicity: string
  income: string
  ip_address?: string
  user_agent?: string
  study_id?: string
  consent_version?: string
}

/**
 * 同意データを取得する
 */
export async function getConsentRecords(): Promise<ConsentRecord[]> {
  try {
    const supabase = await createServerClient()
    
    const { data, error } = await supabase
      .from('consent_records')
      .select('*')
    
    if (error) {
      console.error('Error fetching consent records:', error)
      throw error
    }
    
    return data || []
  } catch (error) {
    console.error('Error in getConsentRecords:', error)
    throw error
  }
}

/**
 * 人口統計データを取得する
 */
export async function getDemographicData(): Promise<DemographicData[]> {
  try {
    const supabase = await createServerClient()
    
    const { data, error } = await supabase
      .from('demographic_data')
      .select('*')
    
    if (error) {
      console.error('Error fetching demographic data:', error)
      throw error
    }
    
    return data || []
  } catch (error) {
    console.error('Error in getDemographicData:', error)
    throw error
  }
}

/**
 * 単一ユーザーの同意と人口統計データを取得
 */
export async function getUserConsentAndDemographicData(userId: string): Promise<{
  consent: ConsentRecord | null;
  demographic: DemographicData | null;
}> {
  try {
    const supabase = await createServerClient()
    
    // 同意データを取得
    const { data: consentData, error: consentError } = await supabase
      .from('consent_records')
      .select('*')
      .eq('user_id', userId)
      .single()
    
    if (consentError && consentError.code !== 'PGRST116') {
      console.error('Error fetching consent data for user:', consentError)
      throw consentError
    }
    
    // 人口統計データを取得
    const { data: demographicData, error: demographicError } = await supabase
      .from('demographic_data')
      .select('*')
      .eq('user_id', userId)
      .maybeSingle()
    
    if (demographicError && demographicError.code !== 'PGRST116') {
      console.error('Error fetching demographic data for user:', demographicError)
      throw demographicError
    }
    
    return { 
      consent: consentData || null, 
      demographic: demographicData || null 
    }
  } catch (error) {
    console.error('Error in getUserConsentAndDemographicData:', error)
    throw error
  }
} 