import { NextResponse } from "next/server";
import { promises as fs } from "fs";
import path from "path";

export async function GET() {
    try {
        // Read the consent data from the actual file path
        const filePath =
            "/Users/junkawasaki/jun784/root/procs/250904-SIP-analytics/data/2a0d7a69-f953-4c29-87a5-8a8e4e8bd413/consent.json";

        // Check if file exists
        try {
            await fs.access(filePath);
        } catch {
            return NextResponse.json(
                { error: "Consent data file not found" },
                { status: 404 },
            );
        }

        const fileContents = await fs.readFile(filePath, "utf8");
        const consentData = JSON.parse(fileContents);

        // Validate the data structure
        if (!consentData || typeof consentData !== "object") {
            return NextResponse.json(
                { error: "Invalid consent data format" },
                { status: 400 },
            );
        }

        return NextResponse.json(consentData);
    } catch (error) {
        console.error("Error reading consent data:", error);
        return NextResponse.json(
            { error: "Failed to load consent data" },
            { status: 500 },
        );
    }
}
