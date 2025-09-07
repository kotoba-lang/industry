import { describe, it, expect, vi, beforeEach } from 'vitest';
import { saveConsentData } from './consent-actions';
import * as dbUtils from '@/lib/services/db/utils';

// Mock the database utility
vi.mock('@/lib/services/db/utils', () => ({
  saveConsentAndDemographicData: vi.fn()
}));

describe('同意データアクション機能 (優先度: 5)', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  describe('saveConsentData', () => {
    it('デモグラフィックと同意データを正しく保存できること', async () => {
      // Mock data
      const demographicInfo = {
        ageGroup: '25-34',
        gender: 'female',
        ethnicity: 'asian',
        income: 'middle'
      };
      
      const consentInfo = {
        consentGiven: true,
        consentVersion: '1.0',
        consentText: 'I agree to the terms and conditions'
      };
      
      const contextInfo = {
        ipAddress: '192.168.1.1',
        userAgent: 'Mozilla/5.0',
        studyId: 'study123'
      };

      // Mock successful DB operation
      vi.mocked(dbUtils.saveConsentAndDemographicData).mockResolvedValue({
        success: true,
        id: 'consent123'
      });

      // Call the action
      const result = await saveConsentData(demographicInfo, consentInfo, contextInfo);

      // Verify DB utility was called with correct parameters
      expect(dbUtils.saveConsentAndDemographicData).toHaveBeenCalledWith(
        demographicInfo,
        consentInfo,
        contextInfo
      );

      // Verify the result
      expect(result).toEqual({
        success: true,
        id: 'consent123'
      });
    });

    it('contextInfoなしでも保存できること', async () => {
      // Mock data
      const demographicInfo = {
        ageGroup: '25-34',
        gender: 'female',
        ethnicity: 'asian',
        income: 'middle'
      };
      
      const consentInfo = {
        consentGiven: true
      };

      // Mock successful DB operation
      vi.mocked(dbUtils.saveConsentAndDemographicData).mockResolvedValue({
        success: true,
        id: 'consent123'
      });

      // Call the action without contextInfo
      const result = await saveConsentData(demographicInfo, consentInfo);

      // Verify DB utility was called with correct parameters
      expect(dbUtils.saveConsentAndDemographicData).toHaveBeenCalledWith(
        demographicInfo,
        consentInfo,
        undefined
      );

      // Verify the result
      expect(result).toEqual({
        success: true,
        id: 'consent123'
      });
    });

    it('DBエラーが発生した場合適切にハンドリングすること', async () => {
      // Mock data
      const demographicInfo = {
        ageGroup: '25-34',
        gender: 'female',
        ethnicity: 'asian',
        income: 'middle'
      };
      
      const consentInfo = {
        consentGiven: true
      };

      // Mock DB error
      vi.mocked(dbUtils.saveConsentAndDemographicData).mockRejectedValue(
        new Error('Database connection error')
      );

      // Call the action
      const result = await saveConsentData(demographicInfo, consentInfo);

      // Verify the error result
      expect(result).toEqual({
        success: false,
        error: 'Failed to save consent data'
      });
    });
  });
}); 