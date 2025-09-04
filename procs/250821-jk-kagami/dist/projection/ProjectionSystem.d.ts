import { Observable } from 'rxjs';
import { KagamiEvent, KagamiState, EditorState, SpreadsheetState, EventStore, Projection } from '@/types';
/**
 * エディタ状態のプロジェクション
 */
export declare class EditorProjection implements Projection<EditorState> {
    initialState: EditorState;
    apply(state: EditorState, event: KagamiEvent): EditorState;
}
/**
 * 表計算状態のプロジェクション
 */
export declare class SpreadsheetProjection implements Projection<SpreadsheetState> {
    initialState: SpreadsheetState;
    apply(state: SpreadsheetState, event: KagamiEvent): SpreadsheetState;
}
/**
 * プロジェクションシステム
 * イベントストリームから状態を再構築し、リアルタイムで更新
 */
export declare class ProjectionSystem {
    private eventStore;
    private stateSubject;
    private editorProjection;
    private spreadsheetProjection;
    private isReplaying;
    private replayProgress;
    constructor(eventStore: EventStore);
    /**
     * 初期状態を取得
     */
    private getInitialState;
    /**
     * イベントを適用して状態を更新
     */
    private applyEvent;
    /**
     * 指定されたシーケンス番号から状態を再構築
     * @param fromSequence 開始シーケンス番号
     */
    rebuild(fromSequence?: number): Promise<void>;
    /**
     * 現在の状態を取得
     */
    getCurrentState(): KagamiState;
    /**
     * 状態の変更を監視
     */
    get state$(): Observable<KagamiState>;
    /**
     * 再構築の進捗を監視
     */
    get rebuildProgress$(): Observable<number>;
    /**
     * エディタ状態のみを取得
     */
    getEditorState(): EditorState;
    /**
     * 表計算状態のみを取得
     */
    getSpreadsheetState(): SpreadsheetState;
    /**
     * エディタ状態の変更を監視
     */
    get editorState$(): Observable<EditorState>;
    /**
     * 表計算状態の変更を監視
     */
    get spreadsheetState$(): Observable<SpreadsheetState>;
    /**
     * 状態のスナップショットを作成
     */
    createSnapshot(): KagamiState;
    /**
     * スナップショットから状態を復元
     * @param snapshot 状態のスナップショット
     */
    restoreFromSnapshot(snapshot: KagamiState): void;
    /**
     * 状態をリセット
     */
    reset(): void;
    /**
     * 統計情報を取得
     */
    getStats(): {
        totalEvents: number;
        editorLastSequence: number;
        spreadsheetLastSequence: number;
        lastUpdated: number;
    };
    /**
     * リソースを解放
     */
    destroy(): void;
}
/**
 * カスタムプロジェクションを作成するためのファクトリー
 */
export declare class ProjectionFactory {
    /**
     * カスタムプロジェクションを作成
     * @param initialState 初期状態
     * @param applyFn イベント適用関数
     */
    static create<T>(initialState: T, applyFn: (state: T, event: KagamiEvent) => T): Projection<T>;
    /**
     * 複数のプロジェクションを合成
     * @param projections プロジェクションの配列
     */
    static compose<T>(projections: Projection<T>[]): Projection<T>;
}
//# sourceMappingURL=ProjectionSystem.d.ts.map