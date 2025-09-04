import { NextRequest, NextResponse } from "next/server";
import { blobStorage } from "@/lib/blob";

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

        // Blob Storage にアップロード
        const buffer = Buffer.from(await file.arrayBuffer());
        const metadata = await blobStorage.uploadArtifact(buffer, {
            participantId,
            type: fileType,
            sessionId,
            filename: fileName,
        });

        return NextResponse.json({
            success: true,
            message: "Artifact saved successfully",
            metadata,
        });
    } catch (error) {
        console.error("Error saving artifact:", error);
        return new NextResponse("Internal Server Error", { status: 500 });
    }
}
