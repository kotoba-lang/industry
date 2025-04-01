import { describe, it, expect, vi, beforeEach } from 'vitest';
import { saveConsentAndDemographicData } from './utils';
import { createServerClient } from '@/lib/supabase/server';

// Mock dependencies
vi.mock('@/lib/supabase/server', () => ({
  createServerClient: vi.fn()
}));

describe('データベースユーティリティ機能 (優先度: 5)', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    
    // Mock date
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2023-01-01'));
    
    // Mock Math.random
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    
    // Mock Supabase client
    const mockSupabaseClient = {
      from: vi.fn().mockReturnThis(),
      insert: vi.fn().mockResolvedValue({ error: null })
    };
    
    vi.mocked(createServerClient).mockResolvedValue(mockSupabaseClient as any);
  });
  
  afterEach(() => {
    vi.useRealTimers();
  });

  describe('saveConsentAndDemographicData', () => {
    it('同意データと人口統計データを正しく保存できること', async () => {
      // Mock data
      const demographicInfo = {
        ageGroup: '25-34',
        gender: 'male',
        ethnicity: 'asian',
        income: 'medium'
      };
      
      const consentInfo = {
        consentGiven: true,
        consentVersion: '1.0',
        consentText: 'I agree to participate in this study.'
      };
      
      const contextInfo = {
        ipAddress: '127.0.0.1',
        userAgent: 'Mozilla/5.0',
        studyId: 'STUDY-123'
      };
      
      // Get Supabase client
      const supabase = await createServerClient();
      
      // Call function
      const result = await saveConsentAndDemographicData(
        demographicInfo,
        consentInfo,
        contextInfo
      );
      
      // Expected user ID with mocked Date.now() and Math.random
      const expectedUserId = 'user_1672531200000_500';
      
      // Verify from was called for consents table
      expect(supabase.from).toHaveBeenCalledWith('consents');
      
      // Verify insert was called with correct data for consents
      expect(supabase.insert).toHaveBeenCalledWith({
        user_id: expectedUserId,
        consent_given: true,
        consent_version: '1.0',
        consent_text: 'I agree to participate in this study.',
        ip_address: '127.0.0.1',
        user_agent: 'Mozilla/5.0',
        study_id: 'STUDY-123',
        timestamp: '2023-01-01T00:00:00.000Z'
      });
      
      // Verify from was called for demographics table
      expect(supabase.from).toHaveBeenCalledWith('demographics');
      
      // Verify insert was called with correct data for demographics
      expect(supabase.insert).toHaveBeenCalledWith({
        user_id: expectedUserId,
        age_group: '25-34',
        gender: 'male',
        ethnicity: 'asian',
        income: 'medium',
        timestamp: '2023-01-01T00:00:00.000Z'
      });
      
      // Verify result
      expect(result).toEqual({
        success: true,
        userId: expectedUserId
      });
    });
    
    it('同意データの保存に失敗した場合エラーを返すこと', async () => {
      // Mock Supabase client with error for first call
      const mockSupabaseClient = {
        from: vi.fn().mockReturnThis(),
        insert: vi.fn()
          .mockResolvedValueOnce({ error: { message: 'Database error' } })
      };
      
      vi.mocked(createServerClient).mockResolvedValue(mockSupabaseClient as any);
      
      // Mock data
      const demographicInfo = {
        ageGroup: '25-34',
        gender: 'male',
        ethnicity: 'asian',
        income: 'medium'
      };
      
      const consentInfo = {
        consentGiven: true
      };
      
      // Call function
      const result = await saveConsentAndDemographicData(
        demographicInfo,
        consentInfo
      );
      
      // Verify result
      expect(result).toEqual({
        success: false,
        error: 'Database error'
      });
    });
    
    it('人口統計データの保存に失敗した場合エラーを返すこと', async () => {
      // Mock Supabase client with error for second call
      const mockSupabaseClient = {
        from: vi.fn().mockReturnThis(),
        insert: vi.fn()
          .mockResolvedValueOnce({ error: null })
          .mockResolvedValueOnce({ error: { message: 'Database error' } })
      };
      
      vi.mocked(createServerClient).mockResolvedValue(mockSupabaseClient as any);
      
      // Mock data
      const demographicInfo = {
        ageGroup: '25-34',
        gender: 'male',
        ethnicity: 'asian',
        income: 'medium'
      };
      
      const consentInfo = {
        consentGiven: true
      };
      
      // Call function
      const result = await saveConsentAndDemographicData(
        demographicInfo,
        consentInfo
      );
      
      // Verify result
      expect(result).toEqual({
        success: false,
        error: 'Database error'
      });
    });
  });
}); 