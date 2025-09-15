// LLM-BOUNDARY: 80_app - app/(segments)/...（RSC & Client）

import { NextRequest, NextResponse } from "next/server";
import { SaveStructuredDataPayloadSchema } from "@/00_schema";
import { storageAdapter } from "@/50_adapters";
import { ExperimentSupervisor } from "@/70_supervisors";

export async function POST(request: NextRequest) {
    try {
        const body = await request.json();

        // Validate the payload
        const validationResult = SaveStructuredDataPayloadSchema.safeParse(body);
        if (!validationResult.success) {
            console.error(
                "Payload validation failed:",
                validationResult.error.format(),
            );
            return new NextResponse(
                JSON.stringify({
                    error: "Invalid payload",
                    details: validationResult.error.format(),
                }),
                { status: 400 },
            );
        }

        const dataToSave = validationResult.data;

        // StorageAdapterを使用してデータを保存
        await storageAdapter.saveStructuredData(dataToSave);

        // スーパーバイザーに通知してキャッシュを更新
        if (dataToSave.type === "session-data" && dataToSave.data.participantId) {
            await ExperimentSupervisor.saveSessionData(dataToSave.data.participantId);
        }

        return NextResponse.json({
            success: true,
            message: "Data saved successfully",
        });
    } catch (error) {
        console.error("Error saving data:", error);
        return new NextResponse("Internal Server Error", { status: 500 });
    }
}
