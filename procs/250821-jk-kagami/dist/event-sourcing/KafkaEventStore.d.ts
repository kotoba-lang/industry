import { Observable } from 'rxjs';
import { EventStore, KagamiEvent, KafkaConfig } from '@/types';
/**
 * Kafkaベースのイベントストア
 * イベントソーシングのSSOT（Single Source of Truth）として機能
 */
export declare class KafkaEventStore implements EventStore {
    private config;
    private kafka;
    private producer;
    private consumer;
    private eventSubject;
    private connected;
    private eventBuffer;
    private sequenceNumber;
    constructor(config: KafkaConfig);
    /**
     * Kafkaに接続
     */
    connect(): Promise<void>;
    /**
     * Kafkaメッセージを処理
     */
    private handleKafkaMessage;
    /**
     * イベントを保存
     */
    save(event: KagamiEvent): Promise<void>;
    /**
     * イベントを取得
     */
    getEvents(fromSequence?: number): Promise<KagamiEvent[]>;
    /**
     * イベントストリームを購読
     */
    subscribe(callback: (event: KagamiEvent) => void): () => void;
    /**
     * イベントストリームをObservableとして取得
     */
    get events$(): Observable<KagamiEvent>;
    /**
     * パーティションを決定
     * イベントタイプに基づいてパーティショニング
     */
    private getPartition;
    /**
     * 文字列をハッシュ化
     */
    private hashString;
    /**
     * 現在のシーケンス番号を取得
     */
    getCurrentSequenceNumber(): number;
    /**
     * バッファをクリア
     */
    clearBuffer(): void;
    /**
     * 接続状態を確認
     */
    isConnected(): boolean;
    /**
     * 接続を切断
     */
    disconnect(): Promise<void>;
    /**
     * リソースを解放
     */
    destroy(): Promise<void>;
}
/**
 * インメモリイベントストア（開発・テスト用）
 */
export declare class InMemoryEventStore implements EventStore {
    private events;
    private eventSubject;
    private sequenceNumber;
    /**
     * イベントを保存
     */
    save(event: KagamiEvent): Promise<void>;
    /**
     * イベントを取得
     */
    getEvents(fromSequence?: number): Promise<KagamiEvent[]>;
    /**
     * イベントストリームを購読
     */
    subscribe(callback: (event: KagamiEvent) => void): () => void;
    /**
     * イベントストリームをObservableとして取得
     */
    get events$(): Observable<KagamiEvent>;
    /**
     * 現在のシーケンス番号を取得
     */
    getCurrentSequenceNumber(): number;
    /**
     * 全てのイベントをクリア
     */
    clear(): void;
    /**
     * リソースを解放
     */
    destroy(): void;
}
//# sourceMappingURL=KafkaEventStore.d.ts.map