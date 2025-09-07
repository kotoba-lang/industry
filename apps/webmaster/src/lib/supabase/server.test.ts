import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createSupabaseServerClient } from './server';
import { createClient } from '@supabase/supabase-js';
import { cookies } from 'next/headers';

// Mock dependencies
vi.mock('@supabase/supabase-js', () => ({
  createClient: vi.fn()
}));

vi.mock('next/headers', () => ({
  cookies: vi.fn()
}));

describe('Supabaseサーバークライアント機能 (優先度: 5)', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    
    // Mock environment variables
    vi.stubEnv('NEXT_PUBLIC_SUPABASE_URL', 'https://test-supabase-url.com');
    vi.stubEnv('NEXT_PUBLIC_SUPABASE_ANON_KEY', 'test-anon-key');
    
    // Mock cookies function
    vi.mocked(cookies).mockReturnValue({
      get: vi.fn(),
      getAll: vi.fn(),
      set: vi.fn(),
      delete: vi.fn(),
      has: vi.fn(),
      size: 0,
      clear: vi.fn(),
      forEach: vi.fn(),
      entries: vi.fn(),
      keys: vi.fn(),
      values: vi.fn(),
      [Symbol.iterator]: vi.fn()
    });
    
    // Mock createClient to return a mock Supabase client
    vi.mocked(createClient).mockReturnValue({
      auth: {
        getSession: vi.fn(),
        getUser: vi.fn(),
        signInWithPassword: vi.fn(),
        signOut: vi.fn()
      },
      from: vi.fn().mockReturnThis(),
      select: vi.fn().mockReturnThis(),
      insert: vi.fn().mockReturnThis(),
      update: vi.fn().mockReturnThis(),
      delete: vi.fn().mockReturnThis(),
      eq: vi.fn().mockReturnThis()
    } as any);
  });

  it('適切な環境変数とセッションオプションでサーバークライアントが作成されること', async () => {
    // Call createSupabaseServerClient
    const client = await createSupabaseServerClient();
    
    // Verify cookies was called
    expect(cookies).toHaveBeenCalled();
    
    // Verify createClient was called with correct parameters
    expect(createClient).toHaveBeenCalledWith(
      'https://test-supabase-url.com',
      'test-anon-key',
      {
        auth: {
          persistSession: false
        }
      }
    );
    
    // Verify client was returned
    expect(client).toBeDefined();
  });
  
  it('環境変数が欠落している場合エラーがスローされること', async () => {
    // Clear environment variables
    vi.unstubAllEnvs();
    
    // Expect error when calling createSupabaseServerClient
    await expect(createSupabaseServerClient()).rejects.toThrow('Missing Supabase environment variables');
  });
}); 