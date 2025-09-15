// LLM-BOUNDARY: 80_app - app/(segments)/...（RSC & Client）

import { NextRequest, NextResponse } from "next/server";
import { loadAllParticipants } from "@/lib/data-loader";
import { storageAdapter } from "@/50_adapters";
import { ExperimentSupervisor } from "@/70_supervisors";

export async function POST(request: NextRequest) {
  try {
    console.log("Starting participant data import...");

    // ファイルシステムから参加者データを取得
    const participants = await loadAllParticipants();

    if (participants.length === 0) {
      return NextResponse.json({
        success: false,
        message: "No participant data found in file system",
        results: []
      });
    }

    const results = [];

    // 各参加者をKuzuとBlobにインポート
    for (const participant of participants) {
      try {
        // Kuzuに保存
        await storageAdapter.saveStructuredData({
          type: "participant",
          data: {
            participantId: participant.id,
            signature: participant.signature,
            agreements: participant.agreements,
            agreedAt: participant.agreedAt
          }
        });

        // Blobに保存（冗長）
        // Blob Storageは自動的に同期されるため、ここでは省略

        results.push({
          participantId: participant.id,
          status: "success",
          message: "Successfully imported participant data"
        });

        console.log(`Imported participant ${participant.id}`);
      } catch (error) {
        console.error(`Failed to import participant ${participant.id}:`, error);
        results.push({
          participantId: participant.id,
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
      message: `Imported ${successCount} participants successfully, ${errorCount} failed`,
      results,
      summary: {
        total: participants.length,
        successful: successCount,
        failed: errorCount
      }
    });

  } catch (error) {
    console.error("Participant import error:", error);
    return NextResponse.json({
      success: false,
      message: error instanceof Error ? error.message : "Unknown error",
      results: []
    }, { status: 500 });
  }
}
