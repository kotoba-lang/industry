import { NextRequest, NextResponse } from 'next/server';

// バッチ処理のレスポンスタイプ
interface HumeBatchResponse {
  id: string;
  status: string;
  results?: any;
  error?: string;
}
