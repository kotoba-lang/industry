// src/app/api/audio-cache/route.ts
import { NextRequest, NextResponse } from 'next/server';
import path from 'path';
import fs from 'fs/promises';
import { stat, mkdir } from 'fs/promises';

const CACHE_DIR = path.resolve(process.cwd(), '.audio_cache');

async function ensureCacheDirExists() {
    try {
        await stat(CACHE_DIR);
    } catch (error: any) {
        if (error.code === 'ENOENT') {
            await mkdir(CACHE_DIR, { recursive: true });
        } else {
            throw error;
        }
    }
}

function getFilePath(text: string, voice: string): string {
    const filename = `${voice}_${text.replace(/[^a-zA-Z0-9]/g, '_')}.mp3`;
    return path.join(CACHE_DIR, filename);
}

export async function GET(request: NextRequest) {
    await ensureCacheDirExists();
    const { searchParams } = new URL(request.url);
    const text = searchParams.get('text');
    const voice = searchParams.get('voice');

    if (!text || !voice) {
        return new NextResponse('Missing text or voice parameter', { status: 400 });
    }

    const filePath = getFilePath(text, voice);

    try {
        const fileBuffer = await fs.readFile(filePath);
        return new NextResponse(fileBuffer, {
            status: 200,
            headers: { 'Content-Type': 'audio/mpeg' },
        });
    } catch (error: any) {
        if (error.code === 'ENOENT') {
            return new NextResponse('File not found', { status: 404 });
        }
        console.error('Error reading file:', error);
        return new NextResponse('Internal Server Error', { status: 500 });
    }
}

export async function POST(request: NextRequest) {
    await ensureCacheDirExists();
    const formData = await request.formData();
    const text = formData.get('text') as string;
    const voice = formData.get('voice') as string;
    const audioData = formData.get('audioData') as Blob;

    if (!text || !voice || !audioData) {
        return new NextResponse('Missing required form data', { status: 400 });
    }

    const filePath = getFilePath(text, voice);
    const buffer = Buffer.from(await audioData.arrayBuffer());

    try {
        await fs.writeFile(filePath, buffer);
        return new NextResponse('File saved successfully', { status: 200 });
    } catch (error) {
        console.error('Error saving file:', error);
        return new NextResponse('Internal Server Error', { status: 500 });
    }
} 