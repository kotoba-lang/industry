import { Observable, Subject, BehaviorSubject } from 'rxjs';
import { 
  KagamiEvent, 
  KagamiState, 
  EditorState, 
  SpreadsheetState, 
  EventStore, 
  Projection 
} from '@/types';

/**
 * エディタ状態のプロジェクション
 */
export class EditorProjection implements Projection<EditorState> {
  public initialState: EditorState = {
    content: '',
    cursor: 0,
    selection: undefined,
    lastSequenceNumber: 0
  };

  public apply(state: EditorState, event: KagamiEvent): EditorState {
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

/**
 * 表計算状態のプロジェクション
 */
export class SpreadsheetProjection implements Projection<SpreadsheetState> {
  public initialState: SpreadsheetState = {
    config: {},
    cells: {},
    lastSequenceNumber: 0
  };

  public apply(state: SpreadsheetState, event: KagamiEvent): SpreadsheetState {
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

/**
 * プロジェクションシステム
 * イベントストリームから状態を再構築し、リアルタイムで更新
 */
export class ProjectionSystem {
  private stateSubject = new BehaviorSubject<KagamiState>(this.getInitialState());
  private editorProjection = new EditorProjection();
  private spreadsheetProjection = new SpreadsheetProjection();
  private isReplaying = false;
  private replayProgress = new Subject<number>();

  constructor(private eventStore: EventStore) {
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
  private getInitialState(): KagamiState {
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
  private applyEvent(event: KagamiEvent): void {
    const currentState = this.stateSubject.value;
    
    const newState: KagamiState = {
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
  public async rebuild(fromSequence: number = 0): Promise<void> {
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
      
    } catch (error) {
      console.error('Failed to rebuild state:', error);
      throw error;
    } finally {
      this.isReplaying = false;
      this.replayProgress.next(100);
    }
  }

  /**
   * 現在の状態を取得
   */
  public getCurrentState(): KagamiState {
    return this.stateSubject.value;
  }

  /**
   * 状態の変更を監視
   */
  public get state$(): Observable<KagamiState> {
    return this.stateSubject.asObservable();
  }

  /**
   * 再構築の進捗を監視
   */
  public get rebuildProgress$(): Observable<number> {
    return this.replayProgress.asObservable();
  }

  /**
   * エディタ状態のみを取得
   */
  public getEditorState(): EditorState {
    return this.stateSubject.value.editor;
  }

  /**
   * 表計算状態のみを取得
   */
  public getSpreadsheetState(): SpreadsheetState {
    return this.stateSubject.value.spreadsheet;
  }

  /**
   * エディタ状態の変更を監視
   */
  public get editorState$(): Observable<EditorState> {
    return new Observable(subscriber => {
      const subscription = this.stateSubject.subscribe(state => {
        subscriber.next(state.editor);
      });
      return () => subscription.unsubscribe();
    });
  }

  /**
   * 表計算状態の変更を監視
   */
  public get spreadsheetState$(): Observable<SpreadsheetState> {
    return new Observable(subscriber => {
      const subscription = this.stateSubject.subscribe(state => {
        subscriber.next(state.spreadsheet);
      });
      return () => subscription.unsubscribe();
    });
  }

  /**
   * 状態のスナップショットを作成
   */
  public createSnapshot(): KagamiState {
    return JSON.parse(JSON.stringify(this.stateSubject.value));
  }

  /**
   * スナップショットから状態を復元
   * @param snapshot 状態のスナップショット
   */
  public restoreFromSnapshot(snapshot: KagamiState): void {
    this.stateSubject.next(snapshot);
  }

  /**
   * 状態をリセット
   */
  public reset(): void {
    this.stateSubject.next(this.getInitialState());
  }

  /**
   * 統計情報を取得
   */
  public getStats(): {
    totalEvents: number;
    editorLastSequence: number;
    spreadsheetLastSequence: number;
    lastUpdated: number;
  } {
    const currentState = this.stateSubject.value;
    return {
      totalEvents: Math.max(
        currentState.editor.lastSequenceNumber,
        currentState.spreadsheet.lastSequenceNumber
      ),
      editorLastSequence: currentState.editor.lastSequenceNumber,
      spreadsheetLastSequence: currentState.spreadsheet.lastSequenceNumber,
      lastUpdated: currentState.metadata.updated
    };
  }

  /**
   * リソースを解放
   */
  public destroy(): void {
    this.stateSubject.complete();
    this.replayProgress.complete();
  }
}

/**
 * カスタムプロジェクションを作成するためのファクトリー
 */
export class ProjectionFactory {
  /**
   * カスタムプロジェクションを作成
   * @param initialState 初期状態
   * @param applyFn イベント適用関数
   */
  public static create<T>(
    initialState: T,
    applyFn: (state: T, event: KagamiEvent) => T
  ): Projection<T> {
    return {
      initialState,
      apply: applyFn
    };
  }

  /**
   * 複数のプロジェクションを合成
   * @param projections プロジェクションの配列
   */
  public static compose<T>(projections: Projection<T>[]): Projection<T> {
    if (projections.length === 0) {
      throw new Error('At least one projection is required');
    }
    
    const firstProjection = projections[0];
    if (!firstProjection) {
      throw new Error('First projection is undefined');
    }
    
    return {
      initialState: firstProjection.initialState,
      apply: (state: T, event: KagamiEvent) => {
        return projections.reduce((currentState, projection) => {
          return projection.apply(currentState, event);
        }, state);
      }
    };
  }
} 