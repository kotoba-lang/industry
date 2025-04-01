import { describe, it, expect, vi, beforeEach } from 'vitest';
import { saveEmotionData, getEmotionData, saveFacialEmotionData, getEmotionDataByAssessment } from './emotion-actions';
import { createSupabaseServerClient } from '@/lib/supabase/server';

// Mock the Supabase client
vi.mock('@/lib/supabase/server', () => ({
  createSupabaseServerClient: vi.fn()
}));

describe('感情データサービス機能 (優先度: 5)', () => {
  const mockSupabaseClient = {
    from: vi.fn().mockReturnThis(),
    select: vi.fn().mockReturnThis(),
    insert: vi.fn().mockReturnThis(),
    eq: vi.fn().mockReturnThis()
  };

  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(createSupabaseServerClient).mockResolvedValue(mockSupabaseClient as any);
  });

  describe('saveEmotionData', () => {
    it('感情データを正しく保存できること', async () => {
      // Mock data
      const emotionData = {
        userId: 'user123',
        assessmentId: '123e4567-e89b-12d3-a456-426614174000',
        stimulusWord: 'happy',
        responseWord: 'joy',
        reactionTimeMs: 500,
        faceEmotions: { happy: 0.8, sad: 0.1 },
        voiceEmotions: { happy: 0.9, sad: 0.05 },
        timestamp: 1617123456789
      };

      // Mock Supabase response
      mockSupabaseClient.insert.mockResolvedValue({ error: null });

      // Call the service
      const result = await saveEmotionData(emotionData);

      // Verify Supabase was called correctly
      expect(createSupabaseServerClient).toHaveBeenCalled();
      expect(mockSupabaseClient.from).toHaveBeenCalledWith('emotion_data');
      expect(mockSupabaseClient.insert).toHaveBeenCalledWith({
        user_id: 'user123',
        assessment_id: '123e4567-e89b-12d3-a456-426614174000',
        stimulus_word: 'happy',
        response_word: 'joy',
        reaction_time_ms: 500,
        face_emotions: { happy: 0.8, sad: 0.1 },
        voice_emotions: { happy: 0.9, sad: 0.05 },
        timestamp: expect.any(String)
      });

      // Verify the result
      expect(result).toEqual({ success: true });
    });

    it('感情データの保存に失敗した場合エラーを返すこと', async () => {
      // Mock data
      const emotionData = {
        userId: 'user123',
        assessmentId: '123e4567-e89b-12d3-a456-426614174000',
        stimulusWord: 'happy',
        responseWord: 'joy',
        reactionTimeMs: 500,
        timestamp: 1617123456789
      };

      // Mock Supabase error
      mockSupabaseClient.insert.mockResolvedValue({ error: { message: 'Database error' } });

      // Call the service
      const result = await saveEmotionData(emotionData);

      // Verify the error result
      expect(result).toEqual({
        success: false,
        error: 'Database error'
      });
    });

    it('例外が発生した場合適切にハンドリングすること', async () => {
      // Mock data
      const emotionData = {
        userId: 'user123',
        assessmentId: '123e4567-e89b-12d3-a456-426614174000',
        stimulusWord: 'happy',
        responseWord: 'joy',
        reactionTimeMs: 500,
        timestamp: 1617123456789
      };

      // Mock Supabase to throw an error
      mockSupabaseClient.insert.mockRejectedValue(new Error('Network error'));

      // Call the service
      const result = await saveEmotionData(emotionData);

      // Verify the error result
      expect(result).toEqual({
        success: false,
        error: 'Failed to save emotion data'
      });
    });
  });

  describe('saveFacialEmotionData', () => {
    it('顔の感情データを正しく保存できること', async () => {
      // Mock data
      const userId = 'user123';
      const assessmentId = '123e4567-e89b-12d3-a456-426614174000';
      const stimulusWord = 'happy';
      const responseWord = 'joy';
      const reactionTimeMs = 500;
      const emotionData = {
        emotions: { happy: 0.8, sad: 0.1 },
        timestamp: 1617123456789
      };

      // Mock Supabase response
      mockSupabaseClient.insert.mockResolvedValue({ error: null });

      // Call the service
      const result = await saveFacialEmotionData(
        userId,
        assessmentId,
        stimulusWord,
        responseWord,
        reactionTimeMs,
        emotionData
      );

      // Verify Supabase was called correctly
      expect(createSupabaseServerClient).toHaveBeenCalled();
      expect(mockSupabaseClient.from).toHaveBeenCalledWith('face_emotion_data');
      expect(mockSupabaseClient.insert).toHaveBeenCalledWith({
        user_id: 'user123',
        assessment_id: '123e4567-e89b-12d3-a456-426614174000',
        stimulus_word: 'happy',
        response_word: 'joy',
        reaction_time_ms: 500,
        emotions: { happy: 0.8, sad: 0.1 },
        timestamp: expect.any(String)
      });

      // Verify the result
      expect(result).toEqual({ success: true });
    });

    it('顔の感情データの保存に失敗した場合エラーを返すこと', async () => {
      // Mock data
      const userId = 'user123';
      const assessmentId = '123e4567-e89b-12d3-a456-426614174000';
      const stimulusWord = 'happy';
      const responseWord = 'joy';
      const reactionTimeMs = 500;
      const emotionData = {
        emotions: { happy: 0.8, sad: 0.1 },
        timestamp: 1617123456789
      };

      // Mock Supabase error
      mockSupabaseClient.insert.mockResolvedValue({ error: { message: 'Database error' } });

      // Call the service
      const result = await saveFacialEmotionData(
        userId,
        assessmentId,
        stimulusWord,
        responseWord,
        reactionTimeMs,
        emotionData
      );

      // Verify the error result
      expect(result).toEqual({
        success: false,
        error: 'Database error'
      });
    });
  });

  describe('getEmotionDataByAssessment', () => {
    it('評価IDに基づいて感情データを正しく取得できること', async () => {
      // Mock data
      const userId = 'user123';
      const assessmentId = '123e4567-e89b-12d3-a456-426614174000';
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

      // Mock Supabase response
      mockSupabaseClient.eq.mockResolvedValue({ data: mockEmotionData, error: null });

      // Call the service
      const result = await getEmotionDataByAssessment(userId, assessmentId);

      // Verify Supabase was called correctly
      expect(createSupabaseServerClient).toHaveBeenCalled();
      expect(mockSupabaseClient.from).toHaveBeenCalledWith('emotion_data');
      expect(mockSupabaseClient.select).toHaveBeenCalledWith('*');
      expect(mockSupabaseClient.eq).toHaveBeenCalledWith('user_id', 'user123');
      expect(mockSupabaseClient.eq).toHaveBeenCalledWith('assessment_id', '123e4567-e89b-12d3-a456-426614174000');

      // Verify the result
      expect(result).toEqual({
        success: true,
        data: mockEmotionData
      });
    });

    it('データが存在しない場合、空配列を返すこと', async () => {
      // Mock data
      const userId = 'user123';
      const assessmentId = '123e4567-e89b-12d3-a456-426614174000';

      // Mock Supabase response with no data
      mockSupabaseClient.eq.mockResolvedValue({ data: null, error: null });

      // Call the service
      const result = await getEmotionDataByAssessment(userId, assessmentId);

      // Verify the result
      expect(result).toEqual({
        success: true,
        data: []
      });
    });

    it('データ取得でエラーが発生した場合、エラーを返すこと', async () => {
      // Mock data
      const userId = 'user123';
      const assessmentId = '123e4567-e89b-12d3-a456-426614174000';

      // Mock Supabase error
      mockSupabaseClient.eq.mockResolvedValue({ data: null, error: { message: 'Database error' } });

      // Call the service
      const result = await getEmotionDataByAssessment(userId, assessmentId);

      // Verify the error result
      expect(result).toEqual({
        success: false,
        error: 'Database error'
      });
    });
  });
}); 