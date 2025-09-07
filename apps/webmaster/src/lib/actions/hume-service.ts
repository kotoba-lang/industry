/**
 * Hume AIのAPIを利用して感情認識を行うサービス
 */
"use server"; // Enable Server Actions

import { createSupabaseServerClient } from "@/lib/supabase/server";

// Humeの感情認識APIのレスポンス型
export interface HumeFaceEmotion {
  name: string;
  score: number;
}

export interface HumeFaceResponse {
  bbox?: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
  emotions: HumeFaceEmotion[];
  confidence?: number;
}

export interface HumeVoiceEmotion {
  name: string;
  score: number;
}

export interface HumeVoiceResponse {
  emotions: HumeVoiceEmotion[];
  confidence?: number;
  metadata?: {
    duration_ms?: number;
    speaking_rate?: number;
    pause_count?: number;
  };
}

/**
 * 顔画像から感情を分析するサーバーアクション
 */
export async function analyzeFace(
  imageBlob: Blob,
  apiKey: string,
): Promise<HumeFaceResponse> {
  try {
    const formData = new FormData();
    formData.append("file", imageBlob, "image.jpg");
    formData.append(
      "json",
      JSON.stringify({
        models: {
          face: {},
        },
      }),
    );

    const response = await fetch("https://api.hume.ai/v0/batch/jobs", {
      method: "POST",
      headers: {
        "X-Hume-Api-Key": apiKey,
      },
      body: formData,
    });

    if (!response.ok) {
      throw new Error(`API error: ${response.status} ${response.statusText}`);
    }

    const jobResponse = await response.json();
    const jobId = jobResponse.job_id;

    // ジョブが完了するまで待機
    return await pollJobResults(jobId, apiKey);
  } catch (error) {
    console.error("Error analyzing face:", error);
    throw error;
  }
}

/**
 * 音声から感情を分析するサーバーアクション
 */
export async function analyzeVoice(
  audioBlob: Blob,
  apiKey: string,
): Promise<HumeVoiceResponse> {
  try {
    const formData = new FormData();
    formData.append("file", audioBlob, "audio.wav");
    formData.append(
      "json",
      JSON.stringify({
        models: {
          prosody: {},
        },
      }),
    );

    const response = await fetch("https://api.hume.ai/v0/batch/jobs", {
      method: "POST",
      headers: {
        "X-Hume-Api-Key": apiKey,
      },
      body: formData,
    });

    if (!response.ok) {
      throw new Error(`API error: ${response.status} ${response.statusText}`);
    }

    const jobResponse = await response.json();
    const jobId = jobResponse.job_id;

    // ジョブが完了するまで待機
    return await pollJobResults(jobId, apiKey);
  } catch (error) {
    console.error("Error analyzing voice:", error);
    throw error;
  }
}

/**
 * 感情分析結果をデータベースに保存するサーバーアクション
 */
export async function saveEmotionAnalysis(data: {
  userId: string;
  assessmentId: string;
  faceEmotions?: Record<string, number>;
  voiceEmotions?: Record<string, number>;
  timestamp: number;
}) {
  try {
    const supabase = await createSupabaseServerClient();

    const { error } = await supabase
      .from("emotion_analysis")
      .insert({
        user_id: data.userId,
        assessment_id: data.assessmentId,
        face_emotions: data.faceEmotions,
        voice_emotions: data.voiceEmotions,
        timestamp: data.timestamp,
      });

    if (error) {
      console.error("Error saving emotion analysis:", error);
      return { success: false, error: error.message };
    }

    return { success: true };
  } catch (error) {
    console.error("Error saving emotion analysis:", error);
    return { success: false, error: "Failed to save emotion analysis" };
  }
}

/**
 * 感情分析結果を取得するサーバーアクション
 */
export async function getEmotionAnalysis(userId: string, assessmentId: string) {
  try {
    const supabase = await createSupabaseServerClient();

    const { data, error } = await supabase
      .from("emotion_analysis")
      .select("*")
      .eq("user_id", userId)
      .eq("assessment_id", assessmentId);

    if (error) {
      console.error("Error retrieving emotion analysis:", error);
      return { success: false, error: error.message };
    }

    return { success: true, data: data || [] };
  } catch (error) {
    console.error("Error retrieving emotion analysis:", error);
    return { success: false, error: "Failed to retrieve emotion analysis" };
  }
}

// Helper function for polling job results
async function pollJobResults(jobId: string, apiKey: string): Promise<any> {
  // 音声分析は画像よりも処理に時間がかかるため、タイムアウト時間を長めに設定
  const maxAttempts = 60; // Increased from 30 to 60
  const delayMs = 2000; // Increased from 1000 to 2000ms

  let lastJobStatus = null;

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    try {
      console.log(
        `Polling job ${jobId}: attempt ${attempt + 1}/${maxAttempts}`,
      );

      const response = await fetch(
        `https://api.hume.ai/v0/batch/jobs/${jobId}`,
        {
          method: "GET",
          headers: {
            "X-Hume-Api-Key": apiKey,
          },
        },
      );

      if (!response.ok) {
        throw new Error(`API error: ${response.status} ${response.statusText}`);
      }

      const jobStatus = await response.json();
      lastJobStatus = jobStatus;

      console.log(`Job status: ${jobStatus.state || jobStatus.status}`);

      // API returns either 'status' or 'state' depending on version
      const status = jobStatus.state || jobStatus.status;

      if (status === "COMPLETED" || status === "completed") {
        // 結果を取得
        const predictionsResponse = await fetch(
          `https://api.hume.ai/v0/batch/jobs/${jobId}/predictions`,
          {
            method: "GET",
            headers: {
              "X-Hume-Api-Key": apiKey,
            },
          },
        );

        if (!predictionsResponse.ok) {
          throw new Error(
            `API error: ${predictionsResponse.status} ${predictionsResponse.statusText}`,
          );
        }

        return await predictionsResponse.json();
      } else if (status === "FAILED" || status === "failed") {
        throw new Error(`Job failed: ${jobStatus.error || "Unknown error"}`);
      }

      // 一定時間待機
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    } catch (error) {
      console.error(`Error polling job ${jobId}:`, error);
      // Don't immediately throw the error, try again unless it's the last attempt
      if (attempt === maxAttempts - 1) {
        throw error;
      }
    }
  }

  console.error("Job timeout - last status received:", lastJobStatus);
  throw new Error(
    `Job ${jobId} did not complete within ${
      maxAttempts * delayMs / 1000
    } seconds. This could be due to server load or the complexity of the audio processing.`,
  );
}
