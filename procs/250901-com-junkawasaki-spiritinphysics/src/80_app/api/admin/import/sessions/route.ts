// LLM-BOUNDARY: 80_app - app/(segments)/...（RSC & Client）

import { NextRequest, NextResponse } from "next/server";
import { loadAllSessionData } from "@/lib/data-loader";
import { storageAdapter } from "@/50_adapters";
import { ExperimentSupervisor } from "@/70_supervisors";

export async function POST(request: NextRequest) {
  try {
    console.log("Starting session data import...");

    // ファイルシステムからセッションデータを取得
    const sessionDataList = await loadAllSessionData();

    if (sessionDataList.length === 0) {
      return NextResponse.json({
        success: false,
        message: "No session data found in file system",
        results: []
      });
    }

    const results = [];

    // 各セッションデータをKuzuとBlobにインポート
    for (const { participantId, sessionData } of sessionDataList) {
      try {
        // Kuzuに保存
        await storageAdapter.saveStructuredData({
          type: "session-data",
          data: {
            participantId,
            events: sessionData.events,
            wordResponses: sessionData.wordResponses || []
          }
        });

        results.push({
          participantId,
          status: "success",
          message: "Successfully imported session data"
        });

        console.log(`Imported session data for participant ${participantId}`);
      } catch (error) {
        console.error(`Failed to import session data for participant ${participantId}:`, error);
        results.push({
          participantId,
          status: "error",
          message: error instanceof Error ? error.message : "Unknown error",
          details: error
        });
      }
    }

    // インポート完了をスーパーバイザーに通知
    await ExperimentSupervisor.startExperiment("bulk-import");

    const successCount = results.filter(r => r.status === "success").length;
    const errorCount = results.filter(r => r.status === "error").length;

    return NextResponse.json({
      success: true,
      message: `Imported session data for ${successCount} participants successfully, ${errorCount} failed`,
      results,
      summary: {
        total: sessionDataList.length,
        successful: successCount,
        failed: errorCount
      }
    });

  } catch (error) {
    console.error("Session import error:", error);
    return NextResponse.json({
      success: false,
      message: error instanceof Error ? error.message : "Unknown error",
      results: []
    }, { status: 500 });
  }
}
