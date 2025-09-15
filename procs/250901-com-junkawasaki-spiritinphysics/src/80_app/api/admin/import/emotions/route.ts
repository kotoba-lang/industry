// LLM-BOUNDARY: 80_app - app/(segments)/...（RSC & Client）

import { NextRequest, NextResponse } from "next/server";
import { loadAllParticipants } from "@/lib/data-loader";
import { loadEmotionAnalysisResults } from "@/lib/emotion-analysis";
import { storageAdapter } from "@/50_adapters";
import { AdminSupervisor } from "@/70_supervisors";

export async function POST(request: NextRequest) {
  try {
    console.log("Starting emotion analysis data import...");

    // 全参加者を取得
    const participants = await loadAllParticipants();

    if (participants.length === 0) {
      return NextResponse.json({
        success: false,
        message: "No participants found",
        results: []
      });
    }

    const results = [];

    // 各参加者の感情分析データをインポート
    for (const participant of participants) {
      try {
        // 感情分析データをファイルシステムから取得
        const emotionResults = await loadEmotionAnalysisResults(participant.id);

        if (emotionResults.length > 0) {
          // 各感情分析結果を保存
          for (const emotionResult of emotionResults) {
            await storageAdapter.saveEmotionAnalysis(participant.id, emotionResult);
          }

          results.push({
            participantId: participant.id,
            status: "success",
            message: `Successfully imported ${emotionResults.length} emotion analysis results`
          });

          console.log(`Imported ${emotionResults.length} emotion analysis results for participant ${participant.id}`);
        } else {
          results.push({
            participantId: participant.id,
            status: "success",
            message: "No emotion analysis data found for this participant"
          });
        }
      } catch (error) {
        console.error(`Failed to import emotion data for participant ${participant.id}:`, error);
        results.push({
          participantId: participant.id,
          status: "error",
          message: error instanceof Error ? error.message : "Unknown error",
          details: error
        });
      }
    }

    // インポート完了をスーパーバイザーに通知
    await AdminSupervisor.updateAnalytics();

    const successCount = results.filter(r => r.status === "success").length;
    const errorCount = results.filter(r => r.status === "error").length;

    return NextResponse.json({
      success: true,
      message: `Imported emotion analysis data for ${successCount} participants successfully, ${errorCount} failed`,
      results,
      summary: {
        total: participants.length,
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
