#!/usr/bin/env tsx

import { readdir, readFile, stat } from "fs/promises";
import path from "path";
import { blobStorage } from "../src/lib/blob";

const ARTIFACTS_DIR = path.resolve(process.cwd(), ".artifacts_cache");

interface MigrationResult {
    participantId: string;
    filesMigrated: number;
    errors: string[];
}

async function migrateParticipantArtifacts(
    participantId: string,
): Promise<MigrationResult> {
    const result: MigrationResult = {
        participantId,
        filesMigrated: 0,
        errors: [],
    };

    const participantDir = path.join(ARTIFACTS_DIR, participantId);

    try {
        const files = await readdir(participantDir);

        for (const file of files) {
            const filePath = path.join(participantDir, file);

            try {
                const fileStat = await stat(filePath);

                // Skip directories
                if (fileStat.isDirectory()) {
                    continue;
                }

                // Read file content
                const fileContent = await readFile(filePath);

                // Determine file type
                let fileType: "consent" | "session_data" | "video" | "audio";
                if (file.includes("consent")) {
                    fileType = "consent";
                } else if (file.includes("session_data")) {
                    fileType = "session_data";
                } else if (file.includes("video") || file.endsWith(".webm")) {
                    fileType = "video";
                } else if (
                    file.includes("audio") || file.endsWith(".mp3") ||
                    file.endsWith(".wav")
                ) {
                    fileType = "audio";
                } else {
                    fileType = "session_data"; // Default
                }

                // Upload to Blob Storage
                await blobStorage.uploadArtifact(fileContent, {
                    participantId,
                    type: fileType,
                    filename: file,
                });

                result.filesMigrated++;
                console.log(`✓ Migrated ${participantId}/${file}`);
            } catch (error) {
                const errorMsg = `Failed to migrate ${file}: ${error}`;
                result.errors.push(errorMsg);
                console.error(`✗ ${errorMsg}`);
            }
        }
    } catch (error) {
        const errorMsg =
            `Failed to read participant directory ${participantId}: ${error}`;
        result.errors.push(errorMsg);
        console.error(`✗ ${errorMsg}`);
    }

    return result;
}

async function migrateDatabaseFile(): Promise<void> {
    const dbFilePath = path.join(ARTIFACTS_DIR, "database.jsonl");

    try {
        const dbContent = await readFile(dbFilePath, "utf-8");
        const lines = dbContent.trim().split("\n");

        for (const line of lines) {
            if (!line.trim()) continue;

            try {
                const data = JSON.parse(line);

                // Extract participant ID from the data
                let participantId: string;
                if (data.type === "consent" && data.data?.participantId) {
                    participantId = data.data.participantId;
                } else if (data.data?.participantId) {
                    participantId = data.data.participantId;
                } else {
                    console.warn(
                        `⚠ Skipping entry without participantId: ${
                            line.substring(0, 100)
                        }...`,
                    );
                    continue;
                }

                // Upload as JSON file
                const jsonData = JSON.stringify(data, null, 2);
                const buffer = Buffer.from(jsonData);

                await blobStorage.uploadArtifact(buffer, {
                    participantId,
                    type: data.type === "consent" ? "consent" : "session_data",
                    filename: `database-entry-${Date.now()}.json`,
                });

                console.log(`✓ Migrated database entry for ${participantId}`);
            } catch (error) {
                console.error(`✗ Failed to migrate database entry: ${error}`);
            }
        }
    } catch (error) {
        console.error(`✗ Failed to read database file: ${error}`);
    }
}

async function main() {
    console.log("🚀 Starting artifact migration to Vercel Blob Storage...\n");

    try {
        // Check if .artifacts_cache exists
        await stat(ARTIFACTS_DIR);
    } catch (error) {
        console.error("❌ .artifacts_cache directory not found");
        process.exit(1);
    }

    const participants = await readdir(ARTIFACTS_DIR);
    const results: MigrationResult[] = [];

    // Migrate participant artifacts
    console.log("📁 Migrating participant artifacts...");
    for (const participant of participants) {
        // Skip database.jsonl
        if (participant === "database.jsonl") continue;

        const participantPath = path.join(ARTIFACTS_DIR, participant);
        const participantStat = await stat(participantPath);

        if (participantStat.isDirectory()) {
            console.log(`\n👤 Migrating participant: ${participant}`);
            const result = await migrateParticipantArtifacts(participant);
            results.push(result);
        }
    }

    // Migrate database file
    console.log("\n📄 Migrating database entries...");
    await migrateDatabaseFile();

    // Summary
    console.log("\n📊 Migration Summary:");
    console.log("=".repeat(50));

    let totalFiles = 0;
    let totalErrors = 0;

    for (const result of results) {
        console.log(`👤 ${result.participantId}:`);
        console.log(`   Files migrated: ${result.filesMigrated}`);
        if (result.errors.length > 0) {
            console.log(`   Errors: ${result.errors.length}`);
            result.errors.forEach((error) => console.log(`     - ${error}`));
        }
        console.log("");

        totalFiles += result.filesMigrated;
        totalErrors += result.errors.length;
    }

    console.log(`📈 Total files migrated: ${totalFiles}`);
    console.log(`⚠️  Total errors: ${totalErrors}`);

    if (totalErrors === 0) {
        console.log("\n✅ Migration completed successfully!");
        console.log("\n💡 Next steps:");
        console.log("1. Verify files in Vercel Blob Storage dashboard");
        console.log(
            "2. Test the application with new Blob Storage integration",
        );
        console.log("3. Optionally remove local .artifacts_cache directory");
    } else {
        console.log(
            "\n⚠️  Migration completed with some errors. Please review the errors above.",
        );
    }

    process.exit(totalErrors > 0 ? 1 : 0);
}

if (require.main === module) {
    main().catch((error) => {
        console.error("💥 Migration failed:", error);
        process.exit(1);
    });
}
