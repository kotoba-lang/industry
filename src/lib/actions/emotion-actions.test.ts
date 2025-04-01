import { describe, it, expect, vi, beforeEach } from 'vitest';
import { saveEmotionData, getEmotionData } from './emotion-actions';
import { EmotionDataService } from './emotion-data-service';

// Mock the emotion data service
vi.mock('./emotion-data-service', () => ({
  EmotionDataService: {
    saveEmotionData: vi.fn(),
    getEmotionDataByAssessment: vi.fn()
  }
}));

describe('感情データアクション機能 (優先度: 5)', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  describe('saveEmotionData', () => {
    it('FormDataを正しく処理して保存できること', async () => {
      // Mock FormData
      const formData = new FormData();
      formData.append('userId', 'user123');
      formData.append('assessmentId', '123e4567-e89b-12d3-a456-426614174000');
      formData.append('stimulusWord', 'happy');
      formData.append('responseWord', 'joy');
      formData.append('reactionTimeMs', '500');
      formData.append('faceEmotions', JSON.stringify({ happy: 0.8, sad: 0.1 }));
      formData.append('voiceEmotions', JSON.stringify({ happy: 0.9, sad: 0.05 }));
      formData.append('timestamp', '1617123456789');

      // Mock the service response
      vi.mocked(EmotionDataService.saveEmotionData).mockResolvedValue({ success: true });

      // Call the action
      const result = await saveEmotionData(formData);

      // Verify the service was called with correct data
      expect(EmotionDataService.saveEmotionData).toHaveBeenCalledWith({
        userId: 'user123',
        assessmentId: '123e4567-e89b-12d3-a456-426614174000',
        stimulusWord: 'happy',
        responseWord: 'joy',
        reactionTimeMs: 500,
        faceEmotions: { happy: 0.8, sad: 0.1 },
        voiceEmotions: { happy: 0.9, sad: 0.05 },
        timestamp: 1617123456789
      });

      // Verify the result
      expect(result).toEqual({ success: true });
    });

    it('JSON形式のデータを正しく処理して保存できること', async () => {
      // Test data
      const jsonData = {
        userId: 'user123',
        assessmentId: '123e4567-e89b-12d3-a456-426614174000',
        stimulusWord: 'happy',
        responseWord: 'joy',
        reactionTimeMs: 500,
        faceEmotions: { happy: 0.8, sad: 0.1 },
        voiceEmotions: { happy: 0.9, sad: 0.05 }
      };

      // Mock the service response
      vi.mocked(EmotionDataService.saveEmotionData).mockResolvedValue({ success: true });

      // Call the action
      const result = await saveEmotionData(jsonData);

      // Verify the service was called with correct data including auto-generated timestamp
      expect(EmotionDataService.saveEmotionData).toHaveBeenCalled();
      expect(EmotionDataService.saveEmotionData.mock.calls[0][0]).toMatchObject({
        ...jsonData,
        timestamp: expect.any(Number)
      });

      // Verify the result
      expect(result).toEqual({ success: true });
    });

    it('バリデーションエラーを適切に処理すること', async () => {
      // Invalid data without required fields
      const invalidData = {
        userId: 'user123',
        // Missing assessmentId and other required fields
      };

      // Call the action
      const result = await saveEmotionData(invalidData);

      // Verify the service was not called
      expect(EmotionDataService.saveEmotionData).not.toHaveBeenCalled();

      // Verify the error response
      expect(result).toMatchObject({
        success: false,
        error: 'Invalid request data',
        details: expect.any(Object)
      });
    });

    it('サービスエラーを適切に処理すること', async () => {
      // Valid data
      const validData = {
        userId: 'user123',
        assessmentId: '123e4567-e89b-12d3-a456-426614174000',
        stimulusWord: 'happy',
        responseWord: 'joy',
        reactionTimeMs: 500
      };

      // Mock the service to return an error
      vi.mocked(EmotionDataService.saveEmotionData).mockResolvedValue({ 
        success: false, 
        error: 'Database error' 
      });

      // Call the action
      const result = await saveEmotionData(validData);

      // Verify the error is handled
      expect(result).toEqual({
        success: false,
        error: 'Internal server error'
      });
    });
  });

  describe('getEmotionData', () => {
    it('有効なパラメータで感情データを取得できること', async () => {
      // Mock data
      const mockEmotionData = [
        { 
          id: 1, 
          user_id: 'user123', 
          assessment_id: '123e4567-e89b-12d3-a456-426614174000',
          stimulus_word: 'happy',
          response_word: 'joy',
          reaction_time_ms: 500,
          face_emotions: { happy: 0.8 },
          voice_emotions: { happy: 0.9 },
          created_at: '2023-01-01T00:00:00Z'
        }
      ];

      // Mock the service response
      vi.mocked(EmotionDataService.getEmotionDataByAssessment).mockResolvedValue({
        success: true,
        data: mockEmotionData
      });

      // Call the action
      const result = await getEmotionData('user123', '123e4567-e89b-12d3-a456-426614174000');

      // Verify the service was called with correct parameters
      expect(EmotionDataService.getEmotionDataByAssessment).toHaveBeenCalledWith(
        'user123', 
        '123e4567-e89b-12d3-a456-426614174000'
      );

      // Verify the result
      expect(result).toEqual({
        success: true,
        data: mockEmotionData
      });
    });

    it('バリデーションエラーを適切に処理すること', async () => {
      // Call with invalid UUID
      const result = await getEmotionData('user123', 'invalid-uuid');

      // Verify the service was not called
      expect(EmotionDataService.getEmotionDataByAssessment).not.toHaveBeenCalled();

      // Verify the error response
      expect(result).toMatchObject({
        success: false,
        error: 'Invalid parameters',
        details: expect.any(Object)
      });
    });

    it('サービスエラーを適切に処理すること', async () => {
      // Mock the service to return an error
      vi.mocked(EmotionDataService.getEmotionDataByAssessment).mockResolvedValue({
        success: false,
        error: 'Database error'
      });

      // Call the action
      const result = await getEmotionData('user123', '123e4567-e89b-12d3-a456-426614174000');

      // Verify the error is passed through
      expect(result).toEqual({
        success: false,
        error: 'Database error'
      });
    });
  });
}); 