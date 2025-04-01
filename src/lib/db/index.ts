import { createClient } from '@supabase/supabase-js';
import { drizzle } from 'drizzle-orm/postgres-js';
import postgres from 'postgres';
import * as schema from './schema';

// Supabaseの接続情報
const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const supabaseKey = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!;

// Supabaseクライアントを作成
export const supabase = createClient(supabaseUrl, supabaseKey, {
  auth: { persistSession: false }
});

// PostgreSQL接続用の設定
const connectionString = process.env.DATABASE_URL!;
// シンプルなSQL実行用クライアント
const queryClient = postgres(connectionString, { ssl: 'require' });
// Drizzle ORM インスタンスを作成
export const db = drizzle(queryClient, { schema });

// エクスポート用の型定義
export type DbClient = typeof db; 