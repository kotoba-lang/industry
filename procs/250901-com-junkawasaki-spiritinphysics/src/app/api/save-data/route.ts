import { NextRequest, NextResponse } from "next/server";
import { blobStorage } from "@/lib/blob";
import { SaveStructuredDataPayloadSchema } from "@/components/jung-voice-assessment/schema";

export async function POST(request: NextRequest) {
    try {
        const body = await request.json();

        // Validate the payload
        const validationResult = SaveStructuredDataPayloadSchema.safeParse(
            body,
        );
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

        // Handle consent data
        if (dataToSave.type === "consent") {
            const { participantId } = dataToSave.data;
            if (!participantId) {
                return new NextResponse(
                    JSON.stringify({
                        error: "Participant ID is required for consent data",
                    }),
                    { status: 400 },
                );
            }

            // Upload consent data as JSON file to Blob Storage
            const jsonData = JSON.stringify(dataToSave.data, null, 2);
            const buffer = Buffer.from(jsonData);

            const metadata = await blobStorage.uploadArtifact(buffer, {
                participantId,
                type: "consent",
                filename: "consent.json",
            });

            return NextResponse.json({
                success: true,
                message: "Consent data saved successfully",
                metadata,
            });
        }

        // Handle session data
        if (dataToSave.type === "session-data") {
            console.log(
                "Received session-data for participant:",
                dataToSave.data.participantId,
            );
            const { participantId, ...rest } = dataToSave.data;
            if (!participantId) {
                console.error("Participant ID is missing in session-data");
                return new NextResponse(
                    JSON.stringify({
                        error: "Participant ID is required for session data",
                    }),
                    { status: 400 },
                );
            }

            // Upload session data as JSON file to Blob Storage
            const jsonData = JSON.stringify(
                { participantId, ...rest },
                null,
                2,
            );
            const buffer = Buffer.from(jsonData);

            const metadata = await blobStorage.uploadArtifact(buffer, {
                participantId,
                type: "session_data",
                filename: "session_data.json",
            });

            console.log(`Successfully saved session data to Blob Storage`);
            return NextResponse.json({
                success: true,
                message: "Session data saved successfully",
                metadata,
            });
        }

        // For other data types, you might want to handle them differently
        // For now, we'll return an error for unsupported types
        return new NextResponse(
            JSON.stringify({ error: "Unsupported data type" }),
            { status: 400 },
        );
    } catch (error) {
        console.error("Error saving data:", error);
        return new NextResponse("Internal Server Error", { status: 500 });
    }
}
