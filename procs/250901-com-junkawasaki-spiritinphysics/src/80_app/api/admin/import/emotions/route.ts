// LLM-BOUNDARY: 80_app - app/(segments)/...（RSC & Client）

import { NextRequest, NextResponse } from "next/server";
import { getParticipantDirectories } from "@/lib/data-loader";
import { readFileSync, existsSync } from "fs";
import { join } from "path";
import { kuzuManager } from "@/lib/database/kuzu-manager";

const ARTIFACTS_CACHE_PATH = '/Users/junkawasaki/jun784/root/procs/250901-com-junkawasaki-spiritinphysics/.artifacts_cache';

export async function POST(request: NextRequest) {
  try {
    console.log("Starting emotion analysis data import to Kuzu...");

    // ファイルシステムから感情分析データを取得
    const participantIds = getParticipantDirectories();
    const emotionDataList = [];

    for (const participantId of participantIds) {
      const emotionDataPath = join(ARTIFACTS_CACHE_PATH, participantId, 'emotion_analysis.json');
      if (existsSync(emotionDataPath)) {
        try {
          const emotionResults = JSON.parse(readFileSync(emotionDataPath, 'utf-8'));
          if (Array.isArray(emotionResults)) {
            emotionDataList.push({ participantId, emotionResults });
          } else {
            emotionDataList.push({ participantId, emotionResults: [emotionResults] });
          }
        } catch (error) {
          console.warn(`Failed to parse emotion analysis data for ${participantId}:`, error);
        }
      }
    }

    if (emotionDataList.length === 0) {
      return NextResponse.json({
        success: false,
        message: "No emotion analysis data found in file system",
        results: []
      });
    }

    const results = [];

    // 各参加者の感情分析データをKuzuにインポート
    for (const { participantId, emotionResults } of emotionDataList) {
      try {
        // 各感情分析結果をKuzuに保存
        for (const emotionResult of emotionResults) {
          await kuzuManager.saveEmotionAnalysis({
            id: `${participantId}_${emotionResult.videoFile}_${Date.now()}`,
            participantId: participantId,
            videoFileId: `${participantId}_${emotionResult.videoFile}`,
            sessionType: emotionResult.sessionType,
            timestamp: emotionResult.timestamp,
            processingTime: emotionResult.processingTime,
            emotions: emotionResult.emotions
          });
        }

        results.push({
          participantId: participantId,
          status: "success",
          message: `Successfully imported ${emotionResults.length} emotion analysis results to Kuzu`
        });

        console.log(`Imported ${emotionResults.length} emotion analysis results for participant ${participantId} to Kuzu`);
      } catch (error) {
        console.error(`Failed to import emotion data for participant ${participantId} to Kuzu:`, error);
        results.push({
          participantId: participantId,
          status: "error",
          message: error instanceof Error ? error.message : "Unknown error",
          details: error
        });
      }
    }

    const successCount = results.filter(r => r.status === "success").length;
    const errorCount = results.filter(r => r.status === "error").length;

    return NextResponse.json({
      success: true,
      message: `Imported emotion analysis data for ${successCount} participants to Kuzu successfully, ${errorCount} failed`,
      results,
      summary: {
        total: emotionDataList.length,
        successful: successCount,
        failed: errorCount
      }
    });

  } catch (error) {
    console.error("Emotion import error:", error);
    return NextResponse.json({
      success: false,
      message: error instanceof Error ? error.message : "Unknown error",
      results: []
    }, { status: 500 });
  }
}
