"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ProjectionFactory = exports.ProjectionSystem = exports.SpreadsheetProjection = exports.EditorProjection = void 0;
const rxjs_1 = require("rxjs");
/**
 * エディタ状態のプロジェクション
 */
class EditorProjection {
    constructor() {
        this.initialState = {
            content: '',
            cursor: 0,
            selection: undefined,
            lastSequenceNumber: 0
        };
    }
    apply(state, event) {
        if (event.type === 'editor.change') {
            const lastChange = event.data.changes[event.data.changes.length - 1];
            return {
                ...state,
                content: event.data.newContent,
                cursor: lastChange ?
                    lastChange.from + lastChange.insert.length :
                    state.cursor,
                lastSequenceNumber: event.sequenceNumber
            };
        }
        return state;
    }
}
exports.EditorProjection = EditorProjection;
/**
 * 表計算状態のプロジェクション
 */
class SpreadsheetProjection {
    constructor() {
        this.initialState = {
            config: {},
            cells: {},
            lastSequenceNumber: 0
        };
    }
    apply(state, event) {
        if (event.type === 'spreadsheet.change') {
            return {
                ...state,
                cells: {
                    ...state.cells,
                    [event.data.cellAddress]: event.data.newValue
                },
                lastSequenceNumber: event.sequenceNumber
            };
        }
        return state;
    }
}
exports.SpreadsheetProjection = SpreadsheetProjection;
/**
 * プロジェクションシステム
 * イベントストリームから状態を再構築し、リアルタイムで更新
 */
class ProjectionSystem {
    constructor(eventStore) {
        this.eventStore = eventStore;
        this.stateSubject = new rxjs_1.BehaviorSubject(this.getInitialState());
        this.editorProjection = new EditorProjection();
        this.spreadsheetProjection = new SpreadsheetProjection();
        this.isReplaying = false;
        this.replayProgress = new rxjs_1.Subject();
        // イベントストリームを購読
        this.eventStore.subscribe((event) => {
            if (!this.isReplaying) {
                this.applyEvent(event);
            }
        });
    }
    /**
     * 初期状態を取得
     */
    getInitialState() {
        return {
            editor: this.editorProjection.initialState,
            spreadsheet: this.spreadsheetProjection.initialState,
            metadata: {
                created: Date.now(),
                updated: Date.now(),
                version: '1.0.0'
            }
        };
    }
    /**
     * イベントを適用して状態を更新
     */
    applyEvent(event) {
        const currentState = this.stateSubject.value;
        const newState = {
            editor: this.editorProjection.apply(currentState.editor, event),
            spreadsheet: this.spreadsheetProjection.apply(currentState.spreadsheet, event),
            metadata: {
                ...currentState.metadata,
                updated: Date.now()
            }
        };
        this.stateSubject.next(newState);
    }
    /**
     * 指定されたシーケンス番号から状態を再構築
     * @param fromSequence 開始シーケンス番号
     */
    async rebuild(fromSequence = 0) {
        this.isReplaying = true;
        try {
            // 初期状態にリセット
            const initialState = this.getInitialState();
            this.stateSubject.next(initialState);
            // イベントを取得
            const events = await this.eventStore.getEvents(fromSequence);
            // イベントを順次適用
            let currentState = initialState;
            for (let i = 0; i < events.length; i++) {
                const event = events[i];
                if (event) {
                    currentState = {
                        editor: this.editorProjection.apply(currentState.editor, event),
                        spreadsheet: this.spreadsheetProjection.apply(currentState.spreadsheet, event),
                        metadata: {
                            ...currentState.metadata,
                            updated: Date.now()
                        }
                    };
                }
                // 進捗を通知
                const progress = ((i + 1) / events.length) * 100;
                this.replayProgress.next(progress);
            }
            // 最終状態を設定
            this.stateSubject.next(currentState);
        }
        catch (error) {
            console.error('Failed to rebuild state:', error);
            throw error;
        }
        finally {
            this.isReplaying = false;
            this.replayProgress.next(100);
        }
    }
    /**
     * 現在の状態を取得
     */
    getCurrentState() {
        return this.stateSubject.value;
    }
    /**
     * 状態の変更を監視
     */
    get state$() {
        return this.stateSubject.asObservable();
    }
    /**
     * 再構築の進捗を監視
     */
    get rebuildProgress$() {
        return this.replayProgress.asObservable();
    }
    /**
     * エディタ状態のみを取得
     */
    getEditorState() {
        return this.stateSubject.value.editor;
    }
    /**
     * 表計算状態のみを取得
     */
    getSpreadsheetState() {
        return this.stateSubject.value.spreadsheet;
    }
    /**
     * エディタ状態の変更を監視
     */
    get editorState$() {
        return new rxjs_1.Observable(subscriber => {
            const subscription = this.stateSubject.subscribe(state => {
                subscriber.next(state.editor);
            });
            return () => subscription.unsubscribe();
        });
    }
    /**
     * 表計算状態の変更を監視
     */
    get spreadsheetState$() {
        return new rxjs_1.Observable(subscriber => {
            const subscription = this.stateSubject.subscribe(state => {
                subscriber.next(state.spreadsheet);
            });
            return () => subscription.unsubscribe();
        });
    }
    /**
     * 状態のスナップショットを作成
     */
    createSnapshot() {
        return JSON.parse(JSON.stringify(this.stateSubject.value));
    }
    /**
     * スナップショットから状態を復元
     * @param snapshot 状態のスナップショット
     */
    restoreFromSnapshot(snapshot) {
        this.stateSubject.next(snapshot);
    }
    /**
     * 状態をリセット
     */
    reset() {
        this.stateSubject.next(this.getInitialState());
    }
    /**
     * 統計情報を取得
     */
    getStats() {
        const currentState = this.stateSubject.value;
        return {
            totalEvents: Math.max(currentState.editor.lastSequenceNumber, currentState.spreadsheet.lastSequenceNumber),
            editorLastSequence: currentState.editor.lastSequenceNumber,
            spreadsheetLastSequence: currentState.spreadsheet.lastSequenceNumber,
            lastUpdated: currentState.metadata.updated
        };
    }
    /**
     * リソースを解放
     */
    destroy() {
        this.stateSubject.complete();
        this.replayProgress.complete();
    }
}
exports.ProjectionSystem = ProjectionSystem;
/**
 * カスタムプロジェクションを作成するためのファクトリー
 */
class ProjectionFactory {
    /**
     * カスタムプロジェクションを作成
     * @param initialState 初期状態
     * @param applyFn イベント適用関数
     */
    static create(initialState, applyFn) {
        return {
            initialState,
            apply: applyFn
        };
    }
    /**
     * 複数のプロジェクションを合成
     * @param projections プロジェクションの配列
     */
    static compose(projections) {
        if (projections.length === 0) {
            throw new Error('At least one projection is required');
        }
        const firstProjection = projections[0];
        if (!firstProjection) {
            throw new Error('First projection is undefined');
        }
        return {
            initialState: firstProjection.initialState,
            apply: (state, event) => {
                return projections.reduce((currentState, projection) => {
                    return projection.apply(currentState, event);
                }, state);
            }
        };
    }
}
exports.ProjectionFactory = ProjectionFactory;
//# sourceMappingURL=ProjectionSystem.js.map