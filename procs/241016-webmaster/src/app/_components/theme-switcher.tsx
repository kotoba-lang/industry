"use client";

import styles from "./switch.module.css";
import { memo, useEffect } from "react";
import { create } from "zustand";
import { persist } from "zustand/middleware";

declare global {
  var updateDOM: () => void;
}

// テーマの型定義
type ColorSchemePreference = "system" | "dark" | "light";

// テーマストアの状態と操作を定義
interface ThemeStore {
  mode: ColorSchemePreference;
  setMode: (mode: ColorSchemePreference) => void;
  toggleMode: () => void;
}

// Zustandストアの作成（persistでlocalStorageに保存）
const useThemeStore = create<ThemeStore>()(
  persist(
    (set) => ({
      mode: "system",
      setMode: (mode) => set({ mode }),
      toggleMode: () => 
        set((state) => {
          const modes: ColorSchemePreference[] = ["system", "dark", "light"];
          const index = modes.indexOf(state.mode);
          return { mode: modes[(index + 1) % modes.length] };
        }),
    }),
    {
      name: "theme-store", // localStorageのキー名
    }
  )
);

// DOMを更新する関数
const updateDOM = (mode: ColorSchemePreference) => {
  // FoUCを防ぐためのトランジション制御
  const modifyTransition = () => {
    const css = document.createElement("style");
    css.textContent = "*,*:after,*:before{transition:none !important;}";
    document.head.appendChild(css);

    return () => {
      /* Force restyle */
      getComputedStyle(document.body);
      /* Wait for next tick before removing */
      setTimeout(() => document.head.removeChild(css), 1);
    };
  };

  if (typeof window === "undefined" || typeof document === "undefined") return;

  try {
    const restoreTransitions = modifyTransition();
    const systemMode = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    const resolvedMode = mode === "system" ? systemMode : mode;
    
    const classList = document.documentElement.classList;
    if (resolvedMode === "dark") classList.add("dark");
    else classList.remove("dark");
    
    document.documentElement.setAttribute("data-mode", mode);
    restoreTransitions();
  } catch (e) {
    console.error("Error updating DOM:", e);
  }
};

/**
 * Theme initialization component - client only
 */
const ThemeInitializer = memo(() => {
  const mode = useThemeStore((state) => state.mode);
  
  // システムの色スキーム変更を検知
  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
    const handleChange = () => updateDOM(mode);
    
    // 初期化
    updateDOM(mode);
    
    // 色スキーム変更を監視
    mediaQuery.addEventListener("change", handleChange);
    return () => mediaQuery.removeEventListener("change", handleChange);
  }, [mode]);
  
  return null;
});

/**
 * テーマ切替ボタン
 */
const ThemeToggle = () => {
  const toggleMode = useThemeStore((state) => state.toggleMode);
  
  return (
    <button
      suppressHydrationWarning
      aria-label="テーマの切り替え"
      title="テーマの切り替え"
      className={styles.switch}
      onClick={toggleMode}
    />
  );
};

/**
 * テーマ切替コンポーネント
 */
export const ThemeSwitcher = () => {
  return (
    <div suppressHydrationWarning>
      <ThemeInitializer />
      <ThemeToggle />
    </div>
  );
};
