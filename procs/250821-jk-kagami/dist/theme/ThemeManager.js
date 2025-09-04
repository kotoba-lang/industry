"use strict";
/**
 * テーマ管理システム
 * ダークモード/ライトモードの切り替えとローカルストレージへの保存を管理する
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.ThemeManager = void 0;
exports.getThemeManager = getThemeManager;
exports.getCurrentTheme = getCurrentTheme;
exports.setTheme = setTheme;
exports.toggleTheme = toggleTheme;
exports.isDarkMode = isDarkMode;
exports.isLightMode = isLightMode;
/**
 * テーマ管理クラス
 */
class ThemeManager {
    constructor() {
        this.currentTheme = 'light';
        this.callbacks = [];
        this.storageKey = 'kagami-theme';
        this.mediaQuery = null;
        this.initializeTheme();
        this.setupMediaQueryListener();
    }
    /**
     * テーマを初期化
     * 1. ローカルストレージから読み込み
     * 2. システムの設定を確認
     * 3. デフォルトはライトモード
     */
    initializeTheme() {
        // ローカルストレージから読み込み
        const savedTheme = localStorage.getItem(this.storageKey);
        if (savedTheme && this.isValidTheme(savedTheme)) {
            this.currentTheme = savedTheme;
        }
        else {
            // システムの設定を確認
            this.currentTheme = this.getSystemPreference();
        }
        this.applyTheme(this.currentTheme);
    }
    /**
     * システムの設定を確認してテーマを取得
     */
    getSystemPreference() {
        if (typeof window === 'undefined')
            return 'light';
        return window.matchMedia('(prefers-color-scheme: dark)').matches
            ? 'dark'
            : 'light';
    }
    /**
     * メディアクエリリスナーを設定
     */
    setupMediaQueryListener() {
        if (typeof window === 'undefined')
            return;
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
    isValidTheme(theme) {
        return theme === 'light' || theme === 'dark';
    }
    /**
     * テーマを適用
     */
    applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        // bodyにもクラスを追加（CSSの優先度を上げるため）
        document.body.classList.remove('kagami-theme-light', 'kagami-theme-dark');
        document.body.classList.add(`kagami-theme-${theme}`);
    }
    /**
     * 現在のテーマを取得
     */
    getCurrentTheme() {
        return this.currentTheme;
    }
    /**
     * テーマを設定
     */
    setTheme(theme) {
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
    toggleTheme() {
        const newTheme = this.currentTheme === 'light' ? 'dark' : 'light';
        this.setTheme(newTheme);
    }
    /**
     * ダークモードかどうかを判定
     */
    isDarkMode() {
        return this.currentTheme === 'dark';
    }
    /**
     * ライトモードかどうかを判定
     */
    isLightMode() {
        return this.currentTheme === 'light';
    }
    /**
     * テーマ変更イベントのリスナーを追加
     */
    addThemeChangeListener(callback) {
        this.callbacks.push(callback);
    }
    /**
     * テーマ変更イベントのリスナーを削除
     */
    removeThemeChangeListener(callback) {
        const index = this.callbacks.indexOf(callback);
        if (index > -1) {
            this.callbacks.splice(index, 1);
        }
    }
    /**
     * テーマ変更を通知
     */
    notifyThemeChange(event) {
        this.callbacks.forEach(callback => {
            try {
                callback(event);
            }
            catch (error) {
                console.error('テーマ変更コールバックでエラーが発生しました:', error);
            }
        });
    }
    /**
     * リソースをクリーンアップ
     */
    destroy() {
        this.callbacks = [];
        if (this.mediaQuery) {
            this.mediaQuery.removeEventListener('change', this.setupMediaQueryListener);
        }
    }
}
exports.ThemeManager = ThemeManager;
/**
 * グローバルなテーママネージャーインスタンス
 */
let globalThemeManager = null;
/**
 * グローバルなテーママネージャーを取得
 */
function getThemeManager() {
    if (!globalThemeManager) {
        globalThemeManager = new ThemeManager();
    }
    return globalThemeManager;
}
/**
 * 現在のテーマを取得する便利関数
 */
function getCurrentTheme() {
    return getThemeManager().getCurrentTheme();
}
/**
 * テーマを設定する便利関数
 */
function setTheme(theme) {
    getThemeManager().setTheme(theme);
}
/**
 * テーマを切り替える便利関数
 */
function toggleTheme() {
    getThemeManager().toggleTheme();
}
/**
 * ダークモードかどうかを判定する便利関数
 */
function isDarkMode() {
    return getThemeManager().isDarkMode();
}
/**
 * ライトモードかどうかを判定する便利関数
 */
function isLightMode() {
    return getThemeManager().isLightMode();
}
//# sourceMappingURL=ThemeManager.js.map