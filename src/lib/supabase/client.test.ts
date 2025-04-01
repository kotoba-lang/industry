import { describe, it, expect, vi, beforeEach } from 'vitest';
import { supabase } from './client';
import { createClient } from '@supabase/supabase-js';

// Mock createClient from supabase-js
vi.mock('@supabase/supabase-js', () => ({
  createClient: vi.fn()
}));

// Mock process.env
vi.stubEnv('NEXT_PUBLIC_SUPABASE_URL', 'https://test-supabase-url.com');
vi.stubEnv('NEXT_PUBLIC_SUPABASE_ANON_KEY', 'test-anon-key');

describe('Supabaseクライアント機能 (優先度: 5)', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.resetModules();
  });

  it('適切な環境変数でSupabaseクライアントが初期化されること', () => {
    // Import the module again to trigger initialization with our mocked values
    require('./client');
    
    // Verify createClient was called with correct parameters
    expect(createClient).toHaveBeenCalledWith(
      'https://test-supabase-url.com',
      'test-anon-key',
      {
        auth: {
          persistSession: true
        }
      }
    );
  });

  it('環境変数が欠落している場合エラーがスローされること', () => {
    // Clear environment variables
    vi.unstubAllEnvs();
    
    // Expect error when importing the module
    expect(() => {
      vi.resetModules();
      require('./client');
    }).toThrow('Missing Supabase environment variables');
  });
}); 