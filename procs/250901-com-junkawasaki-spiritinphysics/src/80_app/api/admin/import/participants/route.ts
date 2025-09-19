// LLM-BOUNDARY: 80_app - app/(segments)/...（RSC & Client）

import { NextRequest, NextResponse } from "next/server";
import { getParticipantDirectories, loadParticipantData } from "@/lib/data-loader";
import { kuzuManager } from "@/lib/database/kuzu-manager";

export async function POST(request: NextRequest) {
  try {
    console.log("Starting participant data import to Kuzu...");

    // ファイルシステムから参加者データを取得
    const participantIds = getParticipantDirectories();
    const participants = participantIds
      .map(id => loadParticipantData(id))
      .filter((participant): participant is any => participant !== null);

    if (participants.length === 0) {
      return NextResponse.json({
        success: false,
        message: "No participant data found in file system",
        results: []
      });
    }

    const results = [];

    // 各参加者をKuzuにインポート
    for (const participant of participants) {
      try {
        // Kuzuに保存（一本化）
        await kuzuManager.saveParticipant({
          id: participant.id,
          signature: participant.signature,
          agreedAt: participant.agreedAt,
          agreements: participant.agreements
        });

        results.push({
          participantId: participant.id,
          status: "success",
          message: "Successfully imported participant data to Kuzu"
        });

        console.log(`Imported participant ${participant.id} to Kuzu`);
      } catch (error) {
        console.error(`Failed to import participant ${participant.id} to Kuzu:`, error);
        results.push({
          participantId: participant.id,
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
      message: `Imported ${successCount} participants to Kuzu successfully, ${errorCount} failed`,
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
