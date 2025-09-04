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
export declare class ThemeManager {
    private currentTheme;
    private callbacks;
    private readonly storageKey;
    private mediaQuery;
    constructor();
    /**
     * テーマを初期化
     * 1. ローカルストレージから読み込み
     * 2. システムの設定を確認
     * 3. デフォルトはライトモード
     */
    private initializeTheme;
    /**
     * システムの設定を確認してテーマを取得
     */
    private getSystemPreference;
    /**
     * メディアクエリリスナーを設定
     */
    private setupMediaQueryListener;
    /**
     * 有効なテーマかどうかを確認
     */
    private isValidTheme;
    /**
     * テーマを適用
     */
    private applyTheme;
    /**
     * 現在のテーマを取得
     */
    getCurrentTheme(): Theme;
    /**
     * テーマを設定
     */
    setTheme(theme: Theme): void;
    /**
     * テーマを切り替え
     */
    toggleTheme(): void;
    /**
     * ダークモードかどうかを判定
     */
    isDarkMode(): boolean;
    /**
     * ライトモードかどうかを判定
     */
    isLightMode(): boolean;
    /**
     * テーマ変更イベントのリスナーを追加
     */
    addThemeChangeListener(callback: ThemeChangeCallback): void;
    /**
     * テーマ変更イベントのリスナーを削除
     */
    removeThemeChangeListener(callback: ThemeChangeCallback): void;
    /**
     * テーマ変更を通知
     */
    private notifyThemeChange;
    /**
     * リソースをクリーンアップ
     */
    destroy(): void;
}
/**
 * グローバルなテーママネージャーを取得
 */
export declare function getThemeManager(): ThemeManager;
/**
 * 現在のテーマを取得する便利関数
 */
export declare function getCurrentTheme(): Theme;
/**
 * テーマを設定する便利関数
 */
export declare function setTheme(theme: Theme): void;
/**
 * テーマを切り替える便利関数
 */
export declare function toggleTheme(): void;
/**
 * ダークモードかどうかを判定する便利関数
 */
export declare function isDarkMode(): boolean;
/**
 * ライトモードかどうかを判定する便利関数
 */
export declare function isLightMode(): boolean;
//# sourceMappingURL=ThemeManager.d.ts.map