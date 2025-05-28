export const dynamic = "force-static";
import { NextRequest, NextResponse } from "next/server";
import fs from "fs";
import path from "path";
import crypto from "crypto";

// キャッシュディレクトリの設定
// publicディレクトリの下にキャッシュを作成すると、直接アクセスされる可能性があるので
// ここではプロジェクトルートの「.cache」ディレクトリを使用
let CACHE_DIR = process.env.AUDIO_CACHE_DIR ||
  path.join(process.cwd(), ".cache", "audio-cache");

// キャッシュディレクトリが存在しない場合は作成
try {
  if (!fs.existsSync(CACHE_DIR)) {
    fs.mkdirSync(CACHE_DIR, { recursive: true });
    console.log(`Created cache directory: ${CACHE_DIR}`);
  }
} catch (error) {
  console.error(`Failed to create cache directory ${CACHE_DIR}:`, error);
  // ここではフォールバックとしてtemporaryディレクトリを使用
  try {
    const os = require("os");
    const tempDir = path.join(os.tmpdir(), "audio-cache");

    if (!fs.existsSync(tempDir)) {
      fs.mkdirSync(tempDir, { recursive: true });
    }

    console.log(`Using temporary directory for cache: ${tempDir}`);
    // グローバル変数を上書き
    CACHE_DIR = tempDir;
  } catch (fallbackError) {
    console.error("Failed to create fallback cache directory:", fallbackError);
  }
}

/**
 * テキストと音声名からキャッシュキーを生成
 */
function generateCacheKey(text: string, voice: string): string {
  // テキストと音声名を組み合わせてハッシュ化
  const hash = crypto.createHash("md5").update(`${text}:${voice}`).digest(
    "hex",
  );
  return hash;
}

/**
 * キャッシュ情報を保存するJSONファイルのパス
 */
const CACHE_INFO_PATH = path.join(CACHE_DIR, "cache-info.json");

/**
 * キャッシュ情報を読み込む
 */
function loadCacheInfo(): Record<
  string,
  { text: string; voice: string; timestamp: number; size: number }
> {
  try {
    if (fs.existsSync(CACHE_INFO_PATH)) {
      const data = fs.readFileSync(CACHE_INFO_PATH, "utf-8");
      return JSON.parse(data);
    }
    return {};
  } catch (error) {
    console.error("Error loading cache info:", error);
    return {};
  }
}

/**
 * キャッシュ情報を保存
 */
function saveCacheInfo(
  info: Record<
    string,
    { text: string; voice: string; timestamp: number; size: number }
  >,
) {
  try {
    fs.writeFileSync(CACHE_INFO_PATH, JSON.stringify(info, null, 2), "utf-8");
  } catch (error) {
    console.error("Error saving cache info:", error);
  }
}

/**
 * GETリクエスト - 音声ファイルの取得
 */
export async function GET(request: NextRequest) {
  try {
    const text = request.nextUrl.searchParams.get("text");
    const voice = request.nextUrl.searchParams.get("voice");

    if (!text || !voice) {
      return NextResponse.json({ error: "Missing text or voice parameter" }, {
        status: 400,
      });
    }

    const cacheKey = generateCacheKey(text, voice);
    const filePath = path.join(CACHE_DIR, `${cacheKey}.mp3`);

    // ファイルが存在するか確認
    if (!fs.existsSync(filePath)) {
      return NextResponse.json({ error: "Audio not found in cache" }, {
        status: 404,
      });
    }

    // ファイルをバイナリで読み込む
    const fileBuffer = fs.readFileSync(filePath);

    // キャッシュ情報を更新（最終アクセス時間）
    const cacheInfo = loadCacheInfo();
    if (cacheInfo[cacheKey]) {
      cacheInfo[cacheKey].timestamp = Date.now();
      saveCacheInfo(cacheInfo);
    }

    // 音声ファイルを返す
    return new NextResponse(fileBuffer, {
      headers: {
        "Content-Type": "audio/mp3",
        "Cache-Control": "max-age=31536000",
      },
    });
  } catch (error) {
    console.error("Error retrieving audio from cache:", error);
    return NextResponse.json({ error: "Internal server error" }, {
      status: 500,
    });
  }
}

/**
 * POSTリクエスト - 音声ファイルの保存
 */
export async function POST(request: NextRequest) {
  try {
    const formData = await request.formData();
    const text = formData.get("text") as string;
    const voice = formData.get("voice") as string;
    const audioFile = formData.get("audio") as File;

    if (!text || !voice || !audioFile) {
      return NextResponse.json(
        { error: "Missing text, voice, or audio file" },
        { status: 400 },
      );
    }

    const cacheKey = generateCacheKey(text, voice);
    const filePath = path.join(CACHE_DIR, `${cacheKey}.mp3`);

    // ファイルとしてディスクに保存
    const buffer = Buffer.from(await audioFile.arrayBuffer());
    fs.writeFileSync(filePath, buffer);

    // キャッシュ情報の更新
    const cacheInfo = loadCacheInfo();
    cacheInfo[cacheKey] = {
      text,
      voice,
      timestamp: Date.now(),
      size: buffer.length,
    };
    saveCacheInfo(cacheInfo);

    return NextResponse.json({ success: true, key: cacheKey });
  } catch (error) {
    console.error("Error saving audio to cache:", error);
    return NextResponse.json({ error: "Internal server error" }, {
      status: 500,
    });
  }
}

/**
 * DELETEリクエスト - キャッシュクリア
 */
export async function DELETE(request: NextRequest) {
  try {
    const olderThanDays = request.nextUrl.searchParams.get("olderThanDays");
    const cacheInfo = loadCacheInfo();
    let deletedCount = 0;

    if (olderThanDays) {
      // 特定の日数より古いファイルのみ削除
      const cutoffTime = Date.now() -
        (parseInt(olderThanDays) * 24 * 60 * 60 * 1000);

      Object.entries(cacheInfo).forEach(([key, info]) => {
        if (info.timestamp < cutoffTime) {
          const filePath = path.join(CACHE_DIR, `${key}.mp3`);
          if (fs.existsSync(filePath)) {
            fs.unlinkSync(filePath);
            delete cacheInfo[key];
            deletedCount++;
          }
        }
      });
    } else {
      // すべてのキャッシュファイルを削除
      Object.keys(cacheInfo).forEach((key) => {
        const filePath = path.join(CACHE_DIR, `${key}.mp3`);
        if (fs.existsSync(filePath)) {
          fs.unlinkSync(filePath);
          deletedCount++;
        }
      });
      // キャッシュ情報をリセット
      Object.keys(cacheInfo).forEach((key) => {
        delete cacheInfo[key];
      });
    }

    saveCacheInfo(cacheInfo);
    return NextResponse.json({ success: true, deletedCount });
  } catch (error) {
    console.error("Error clearing cache:", error);
    return NextResponse.json({ error: "Internal server error" }, {
      status: 500,
    });
  }
}

/**
 * キャッシュ統計情報取得API
 */
export async function PATCH(request: NextRequest) {
  try {
    const cacheInfo = loadCacheInfo();
    let totalSize = 0;
    let count = 0;

    // ファイルサイズの合計を計算
    Object.values(cacheInfo).forEach((info) => {
      totalSize += info.size;
      count++;
    });

    return NextResponse.json({
      count,
      sizeBytes: totalSize,
    });
  } catch (error) {
    console.error("Error getting cache stats:", error);
    return NextResponse.json({ error: "Internal server error" }, {
      status: 500,
    });
  }
}
