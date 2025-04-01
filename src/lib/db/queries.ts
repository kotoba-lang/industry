'use server'

import { createServerClient } from '@/lib/supabase/server'

/**
 * 同意データを取得する
 */
export async function getConsentRecords() {
  try {
    const supabase = await createServerClient()
    
    const { data, error } = await supabase
      .from('consent_records')
      .select('*')
    
    if (error) {
      console.error('Error fetching consent records:', error)
      throw error
    }
    
    return data
  } catch (error) {
    console.error('Error in getConsentRecords:', error)
    throw error
  }
}

/**
 * 人口統計データを取得する
 */
export async function getDemographicData() {
  try {
    const supabase = await createServerClient()
    
    const { data, error } = await supabase
      .from('demographic_data')
      .select('*')
    
    if (error) {
      console.error('Error fetching demographic data:', error)
      throw error
    }
    
    return data
  } catch (error) {
    console.error('Error in getDemographicData:', error)
    throw error
  }
}

/**
 * 単一ユーザーの同意と人口統計データを取得
 */
export async function getUserConsentAndDemographicData(userId: string) {
  try {
    const supabase = await createServerClient()
    
    // 同意データを取得
    const { data: consentData, error: consentError } = await supabase
      .from('consent_records')
      .select('*')
      .eq('user_id', userId)
      .single()
    
    if (consentError) {
      console.error('Error fetching consent data for user:', consentError)
      throw consentError
    }
    
    // 人口統計データを取得
    const { data: demographicData, error: demographicError } = await supabase
      .from('demographic_data')
      .select('*')
      .eq('user_id', userId)
      .maybeSingle()
    
    if (demographicError) {
      console.error('Error fetching demographic data for user:', demographicError)
      throw demographicError
    }
    
    return { consent: consentData, demographic: demographicData }
  } catch (error) {
    console.error('Error in getUserConsentAndDemographicData:', error)
    throw error
  }
} 