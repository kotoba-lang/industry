"use client";

import styles from "./switch.module.css";
import { memo, useEffect, useState } from "react";

declare global {
  var updateDOM: () => void;
}

type ColorSchemePreference = "system" | "dark" | "light";

const STORAGE_KEY = "nextjs-blog-starter-theme";
const modes: ColorSchemePreference[] = ["system", "dark", "light"];

/** to reuse updateDOM function defined inside injected script */

/** function to be injected in script tag for avoiding FOUC (Flash of Unstyled Content) */
export const NoFOUCScript = (storageKey: string) => {
  /* can not use outside constants or function as this script will be injected in a different context */
  const [SYSTEM, DARK, LIGHT] = ["system", "dark", "light"];
  
  // Safety check - ensure this only runs in browser
  try {
    if (typeof window === "undefined" || typeof document === "undefined") return;

    /** Modify transition globally to avoid patched transitions */
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

    const media = matchMedia(`(prefers-color-scheme: ${DARK})`);

    /** function to add remove dark class */
    window.updateDOM = () => {
      const restoreTransitions = modifyTransition();
      const mode = localStorage.getItem(storageKey) ?? SYSTEM;
      const systemMode = media.matches ? DARK : LIGHT;
      const resolvedMode = mode === SYSTEM ? systemMode : mode;
      const classList = document.documentElement.classList;
      if (resolvedMode === DARK) classList.add(DARK);
      else classList.remove(DARK);
      document.documentElement.setAttribute("data-mode", mode);
      restoreTransitions();
    };
    window.updateDOM();
    media.addEventListener("change", window.updateDOM);
  } catch (e) {
    console.error("Error in theme script:", e);
  }
};

let updateDOM: () => void;

/**
 * Switch button to quickly toggle user preference.
 */
const Switch = () => {
  const [mode, setMode] = useState<ColorSchemePreference>(
    () =>
      ((typeof window !== "undefined" &&
        localStorage.getItem(STORAGE_KEY)) ??
        "system") as ColorSchemePreference,
  );

  useEffect(() => {
    // Initialize state from localStorage on the client side
    const storedMode = localStorage.getItem(STORAGE_KEY) as ColorSchemePreference || "system";
    setMode(storedMode);
    
    // store global functions to local variables to avoid any interference
    if (typeof window !== "undefined" && window.updateDOM) {
      updateDOM = window.updateDOM;
    }
    /** Sync the tabs */
    addEventListener("storage", (e: StorageEvent): void => {
      e.key === STORAGE_KEY && setMode(e.newValue as ColorSchemePreference);
    });
  }, []);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, mode);
    if (typeof updateDOM === "function") {
      updateDOM();
    } else if (typeof window !== "undefined" && typeof window.updateDOM === "function") {
      window.updateDOM();
    }
  }, [mode]);

  /** toggle mode */
  const handleModeSwitch = () => {
    const index = modes.indexOf(mode);
    setMode(modes[(index + 1) % modes.length]);
  };
  return (
    <button
      suppressHydrationWarning
      aria-label="Toggle dark mode"
      title="Toggle dark mode"
      className={styles.switch}
      onClick={handleModeSwitch}
    />
  );
};

const Script = memo(() => (
  <script
    dangerouslySetInnerHTML={{
      __html: `(${NoFOUCScript.toString()})('${STORAGE_KEY}')`,
    }}
  />
));

/**
 * This component wich applies classes and transitions.
 */
export const ThemeSwitcher = () => {
  return (
    <div suppressHydrationWarning>
      <Script />
      <Switch />
    </div>
  );
};
