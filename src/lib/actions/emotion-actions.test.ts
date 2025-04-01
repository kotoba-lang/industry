import { beforeEach, describe, expect, it, vi } from "vitest";
import * as emotionActions from "./emotion-actions";
import { z } from "zod";
import * as supabaseServer from "@/lib/supabase/server";

// Mock createSupabaseServerClient
vi.mock("@/lib/supabase/server", () => ({
  createSupabaseServerClient: vi.fn(),
}));

describe("感情データアクション機能 (優先度: 5)", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    // Default mock for successful Supabase response
    vi.mocked(supabaseServer.createSupabaseServerClient).mockResolvedValue({
      from: vi.fn().mockReturnValue({
        insert: vi.fn().mockReturnValue({ error: null }),
        select: vi.fn().mockReturnValue({
          eq: vi.fn().mockReturnValue({
            eq: vi.fn().mockReturnValue({
              data: [],
              error: null,
            }),
          }),
        }),
      }),
    } as any);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("saveEmotionData", () => {
    it("FormDataを正しく処理して保存できること", async () => {
      // Mock FormData
      const formData = new FormData();
      formData.append("userId", "user123");
      formData.append("assessmentId", "123e4567-e89b-12d3-a456-426614174000");
      formData.append("stimulusWord", "happy");
      formData.append("responseWord", "joy");
      formData.append("reactionTimeMs", "500");
      formData.append("faceEmotions", JSON.stringify({ happy: 0.8, sad: 0.1 }));
      formData.append(
        "voiceEmotions",
        JSON.stringify({ happy: 0.9, sad: 0.05 }),
      );
      formData.append("timestamp", "1617123456789");

      // Call the function
      const result = await emotionActions.saveEmotionData(formData);

      // Verify the result
      expect(result).toEqual({ success: true });
    });

    it("JSON形式のデータを正しく処理して保存できること", async () => {
      // Test data
      const jsonData = {
        userId: "user123",
        assessmentId: "123e4567-e89b-12d3-a456-426614174000",
        stimulusWord: "happy",
        responseWord: "joy",
        reactionTimeMs: 500,
        faceEmotions: { happy: 0.8, sad: 0.1 },
        voiceEmotions: { happy: 0.9, sad: 0.05 },
      };

      // Call the function
      const result = await emotionActions.saveEmotionData(jsonData);

      // Verify the result
      expect(result).toEqual({ success: true });
    });

    it("バリデーションエラーを適切に処理すること", async () => {
      // Mock Zod.parse to throw an error
      const mockParse = vi.fn().mockImplementation(() => {
        throw new z.ZodError([{
          code: "invalid_type",
          expected: "string",
          received: "undefined",
          path: ["assessmentId"],
          message: "Required",
        }]);
      });

      // Spy on Zod schema parse method
      vi.spyOn(z.ZodObject.prototype, "parse").mockImplementation(mockParse);

      // Invalid data without required fields
      const invalidData = {
        userId: "user123",
        // Missing assessmentId and other required fields
      };

      // Call the function
      const result = await emotionActions.saveEmotionData(invalidData);

      // Verify the error response
      expect(result.success).toBe(false);
      expect(result.error).toBe("Invalid request data");
      expect(result.details).toBeDefined();
    });

    it("サービスエラーを適切に処理すること", async () => {
      // Mock Supabase to return an error
      vi.mocked(supabaseServer.createSupabaseServerClient)
        .mockResolvedValueOnce({
          from: vi.fn().mockReturnValue({
            insert: vi.fn().mockReturnValue({
              error: { message: "Database error" },
            }),
          }),
        } as any);

      // Valid data
      const validData = {
        userId: "user123",
        assessmentId: "123e4567-e89b-12d3-a456-426614174000",
        stimulusWord: "happy",
        responseWord: "joy",
        reactionTimeMs: 500,
      };

      // Call the function
      const result = await emotionActions.saveEmotionData(validData);

      // Verify the error is handled
      expect(result).toEqual({
        success: false,
        error: "Database error",
      });
    });
  });

  describe("getEmotionData", () => {
    it("有効なパラメータで感情データを取得できること", async () => {
      // Mock emotion data
      const mockEmotionData = [
        {
          id: 1,
          user_id: "user123",
          assessment_id: "123e4567-e89b-12d3-a456-426614174000",
          stimulus_word: "happy",
          response_word: "joy",
          reaction_time_ms: 500,
          face_emotions: { happy: 0.8 },
          voice_emotions: { happy: 0.9 },
          created_at: "2023-01-01T00:00:00Z",
        },
      ];

      // Mock Supabase to return the emotion data
      vi.mocked(supabaseServer.createSupabaseServerClient)
        .mockResolvedValueOnce({
          from: vi.fn().mockReturnValue({
            select: vi.fn().mockReturnValue({
              eq: vi.fn().mockReturnValue({
                eq: vi.fn().mockReturnValue({
                  data: mockEmotionData,
                  error: null,
                }),
              }),
            }),
          }),
        } as any);

      // Call the function
      const result = await emotionActions.getEmotionData(
        "user123",
        "123e4567-e89b-12d3-a456-426614174000",
      );

      // Verify the result
      expect(result).toEqual({
        success: true,
        data: mockEmotionData,
      });
    });

    it("バリデーションエラーを適切に処理すること", async () => {
      // Mock Zod.parse to throw an error
      vi.spyOn(z.ZodObject.prototype, "parse").mockImplementationOnce(() => {
        throw new z.ZodError([{
          code: "invalid_type",
          expected: "string",
          received: "undefined",
          path: ["assessmentId"],
          message: "Required",
        }]);
      });

      // Call with invalid UUID
      const result = await emotionActions.getEmotionData(
        "user123",
        "invalid-uuid",
      );

      // Verify the error response
      expect(result.success).toBe(false);
      expect(result.error).toBe("Invalid parameters");
      expect(result.details).toBeDefined();
    });

    it("サービスエラーを適切に処理すること", async () => {
      // Mock Supabase to return an error
      vi.mocked(supabaseServer.createSupabaseServerClient)
        .mockResolvedValueOnce({
          from: vi.fn().mockReturnValue({
            select: vi.fn().mockReturnValue({
              eq: vi.fn().mockReturnValue({
                eq: vi.fn().mockReturnValue({
                  data: null,
                  error: { message: "Database error" },
                }),
              }),
            }),
          }),
        } as any);

      // Call the function
      const result = await emotionActions.getEmotionData(
        "user123",
        "123e4567-e89b-12d3-a456-426614174000",
      );

      // Verify the error is passed through
      expect(result).toEqual({
        success: false,
        error: "Database error",
      });
    });
  });
});
