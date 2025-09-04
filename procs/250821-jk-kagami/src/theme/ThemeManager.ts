/**
 * テーマ管理システム
 * ダークモード/ライトモードの切り替えとローカルストレージへの保存を管理する
 */

/** サポートされているテーマ */
export type Theme = 'light' | 'dark';

/** テーマ変更イベント */
export interface ThemeChangeEvent {
  theme: Theme;
  previousTheme: Theme;
}

/** テーマ変更コールバック */
export type ThemeChangeCallback = (event: ThemeChangeEvent) => void;

/**
 * テーマ管理クラス
 */
export class ThemeManager {
  private currentTheme: Theme = 'light';
  private callbacks: ThemeChangeCallback[] = [];
  private readonly storageKey = 'kagami-theme';
  private mediaQuery: MediaQueryList | null = null;

  constructor() {
    this.initializeTheme();
    this.setupMediaQueryListener();
  }

  /**
   * テーマを初期化
   * 1. ローカルストレージから読み込み
   * 2. システムの設定を確認
   * 3. デフォルトはライトモード
   */
  private initializeTheme(): void {
    // ローカルストレージから読み込み
    const savedTheme = localStorage.getItem(this.storageKey) as Theme;
    
    if (savedTheme && this.isValidTheme(savedTheme)) {
      this.currentTheme = savedTheme;
    } else {
      // システムの設定を確認
      this.currentTheme = this.getSystemPreference();
    }
    
    this.applyTheme(this.currentTheme);
  }

  /**
   * システムの設定を確認してテーマを取得
   */
  private getSystemPreference(): Theme {
    if (typeof window === 'undefined') return 'light';
    
    return window.matchMedia('(prefers-color-scheme: dark)').matches 
      ? 'dark' 
      : 'light';
  }

  /**
   * メディアクエリリスナーを設定
   */
  private setupMediaQueryListener(): void {
    if (typeof window === 'undefined') return;

    this.mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    
    this.mediaQuery.addEventListener('change', (e) => {
      // ユーザーが明示的に設定していない場合のみ、システム設定に従う
      if (!localStorage.getItem(this.storageKey)) {
        const newTheme = e.matches ? 'dark' : 'light';
        this.setTheme(newTheme);
      }
    });
  }

  /**
   * 有効なテーマかどうかを確認
   */
  private isValidTheme(theme: string): theme is Theme {
    return theme === 'light' || theme === 'dark';
  }

  /**
   * テーマを適用
   */
  private applyTheme(theme: Theme): void {
    document.documentElement.setAttribute('data-theme', theme);
    
    // bodyにもクラスを追加（CSSの優先度を上げるため）
    document.body.classList.remove('kagami-theme-light', 'kagami-theme-dark');
    document.body.classList.add(`kagami-theme-${theme}`);
  }

  /**
   * 現在のテーマを取得
   */
  getCurrentTheme(): Theme {
    return this.currentTheme;
  }

  /**
   * テーマを設定
   */
  setTheme(theme: Theme): void {
    const previousTheme = this.currentTheme;
    
    if (previousTheme === theme) {
      return;
    }

    this.currentTheme = theme;
    this.applyTheme(theme);
    
    // ローカルストレージに保存
    localStorage.setItem(this.storageKey, theme);
    
    // コールバック実行
    this.notifyThemeChange({ theme, previousTheme });
  }

  /**
   * テーマを切り替え
   */
  toggleTheme(): void {
    const newTheme = this.currentTheme === 'light' ? 'dark' : 'light';
    this.setTheme(newTheme);
  }

  /**
   * ダークモードかどうかを判定
   */
  isDarkMode(): boolean {
    return this.currentTheme === 'dark';
  }

  /**
   * ライトモードかどうかを判定
   */
  isLightMode(): boolean {
    return this.currentTheme === 'light';
  }

  /**
   * テーマ変更イベントのリスナーを追加
   */
  addThemeChangeListener(callback: ThemeChangeCallback): void {
    this.callbacks.push(callback);
  }

  /**
   * テーマ変更イベントのリスナーを削除
   */
  removeThemeChangeListener(callback: ThemeChangeCallback): void {
    const index = this.callbacks.indexOf(callback);
    if (index > -1) {
      this.callbacks.splice(index, 1);
    }
  }

  /**
   * テーマ変更を通知
   */
  private notifyThemeChange(event: ThemeChangeEvent): void {
    this.callbacks.forEach(callback => {
      try {
        callback(event);
      } catch (error) {
        console.error('テーマ変更コールバックでエラーが発生しました:', error);
      }
    });
  }

  /**
   * リソースをクリーンアップ
   */
  destroy(): void {
    this.callbacks = [];
    
    if (this.mediaQuery) {
      this.mediaQuery.removeEventListener('change', this.setupMediaQueryListener);
    }
  }
}

/**
 * グローバルなテーママネージャーインスタンス
 */
let globalThemeManager: ThemeManager | null = null;

/**
 * グローバルなテーママネージャーを取得
 */
export function getThemeManager(): ThemeManager {
  if (!globalThemeManager) {
    globalThemeManager = new ThemeManager();
  }
  return globalThemeManager;
}

/**
 * 現在のテーマを取得する便利関数
 */
export function getCurrentTheme(): Theme {
  return getThemeManager().getCurrentTheme();
}

/**
 * テーマを設定する便利関数
 */
export function setTheme(theme: Theme): void {
  getThemeManager().setTheme(theme);
}

/**
 * テーマを切り替える便利関数
 */
export function toggleTheme(): void {
  getThemeManager().toggleTheme();
}

/**
 * ダークモードかどうかを判定する便利関数
 */
export function isDarkMode(): boolean {
  return getThemeManager().isDarkMode();
}

/**
 * ライトモードかどうかを判定する便利関数
 */
export function isLightMode(): boolean {
  return getThemeManager().isLightMode();
} 