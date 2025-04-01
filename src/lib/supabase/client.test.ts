/**
 * @jest-environment node
 */

import { createClient } from "@supabase/ssr";

// Mock createClient from supabase-js
jest.mock("@supabase/ssr", () => ({
  createClient: jest.fn(),
}));

// Store original env values
const originalEnv = process.env;

describe("Supabaseクライアント機能 (優先度: 5)", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    jest.resetModules();

    // Setup test environment variables
    process.env = {
      ...originalEnv,
      NEXT_PUBLIC_SUPABASE_URL: "https://test-supabase-url.com",
      NEXT_PUBLIC_SUPABASE_ANON_KEY: "test-anon-key",
    };
  });

  afterEach(() => {
    // Restore original env
    process.env = originalEnv;
  });

  it("適切な環境変数でSupabaseクライアントが初期化されること", () => {
    // Import the module again to trigger initialization with our mocked values
    require("./client");

    // Verify createClient was called with correct parameters
    expect(createClient).toHaveBeenCalledWith(
      "https://test-supabase-url.com",
      "test-anon-key",
      {
        auth: {
          persistSession: true,
        },
      },
    );
  });

  it("環境変数が欠落している場合エラーがスローされること", () => {
    // Clear environment variables
    delete process.env.NEXT_PUBLIC_SUPABASE_URL;
    delete process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

    // Expect error when importing the module
    expect(() => {
      jest.resetModules();
      require("./client");
    }).toThrow("Missing Supabase environment variables");
  });
});
