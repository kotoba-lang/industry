// LLM-BOUNDARY: 80_app - app/(segments)/...（RSC & Client）

import { NextRequest, NextResponse } from "next/server";
import { storageAdapter } from "@/50_adapters";
import { ExperimentSupervisor } from "@/70_supervisors";

export async function POST(request: NextRequest) {
    try {
        const formData = await request.formData();
        const file = formData.get("file") as Blob | null;
        const sessionId = formData.get("sessionId") as string | null;
        const fileName = formData.get("fileName") as string | null;
        const participantId = formData.get("participantId") as string | null;

        if (!file || !sessionId || !fileName || !participantId) {
            return new NextResponse(
                "Missing required form data: file, sessionId, fileName, or participantId",
                { status: 400 },
            );
        }

        // ファイルタイプを判定
        const fileType = fileName.includes("video")
            ? "video"
            : fileName.includes("audio")
            ? "audio"
            : fileName.includes("consent")
            ? "consent"
            : "session_data";

        // StorageAdapterを使用してアーティファクトを保存
        const buffer = Buffer.from(await file.arrayBuffer());
        const result = await storageAdapter.saveArtifact(participantId, fileType, fileName, buffer);

        // ビデオ保存の場合はスーパーバイザーに通知
        if (fileType === "video") {
            await ExperimentSupervisor.saveSessionData(participantId);
        }

        return NextResponse.json({
            success: true,
            message: "Artifact saved successfully",
            result,
        });
    } catch (error) {
        console.error("Error saving artifact:", error);
        return new NextResponse("Internal Server Error", { status: 500 });
    }
}
