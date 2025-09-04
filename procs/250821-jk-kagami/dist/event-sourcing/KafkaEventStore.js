"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.InMemoryEventStore = exports.KafkaEventStore = void 0;
const kafkajs_1 = require("kafkajs");
const rxjs_1 = require("rxjs");
/**
 * Kafkaベースのイベントストア
 * イベントソーシングのSSOT（Single Source of Truth）として機能
 */
class KafkaEventStore {
    constructor(config) {
        this.config = config;
        this.eventSubject = new rxjs_1.Subject();
        this.connected = false;
        this.eventBuffer = [];
        this.sequenceNumber = 0;
        const kafkaOptions = {
            clientId: config.clientId,
            brokers: config.brokers,
        };
        if (config.ssl) {
            kafkaOptions.ssl = config.ssl;
        }
        if (config.sasl) {
            kafkaOptions.sasl = config.sasl;
        }
        this.kafka = new kafkajs_1.Kafka(kafkaOptions);
        this.producer = this.kafka.producer();
        this.consumer = this.kafka.consumer({
            groupId: config.groupId || `kagami-${Date.now()}`
        });
    }
    /**
     * Kafkaに接続
     */
    async connect() {
        try {
            await this.producer.connect();
            await this.consumer.connect();
            // トピックをサブスクライブ
            await this.consumer.subscribe({
                topic: this.config.topic,
                fromBeginning: true
            });
            // メッセージを受信
            await this.consumer.run({
                eachMessage: async ({ topic, partition, message }) => {
                    await this.handleKafkaMessage(message);
                }
            });
            this.connected = true;
            console.log('Kafka EventStore connected');
        }
        catch (error) {
            console.error('Failed to connect to Kafka:', error);
            throw error;
        }
    }
    /**
     * Kafkaメッセージを処理
     */
    async handleKafkaMessage(message) {
        try {
            if (!message.value)
                return;
            const eventData = JSON.parse(message.value.toString());
            const event = {
                ...eventData,
                // Kafkaのoffsetをシーケンス番号として使用
                sequenceNumber: parseInt(message.offset || '0')
            };
            // シーケンス番号を更新
            this.sequenceNumber = Math.max(this.sequenceNumber, event.sequenceNumber);
            // イベントをバッファに追加
            this.eventBuffer.push(event);
            // サブスクライバーに通知
            this.eventSubject.next(event);
        }
        catch (error) {
            console.error('Failed to process Kafka message:', error);
        }
    }
    /**
     * イベントを保存
     */
    async save(event) {
        if (!this.connected) {
            throw new Error('EventStore is not connected');
        }
        try {
            // シーケンス番号を設定
            event.sequenceNumber = ++this.sequenceNumber;
            // Kafkaにイベントを送信
            await this.producer.send({
                topic: this.config.topic,
                messages: [{
                        key: event.id,
                        value: JSON.stringify(event),
                        partition: this.getPartition(event),
                        timestamp: event.timestamp.toString()
                    }]
            });
        }
        catch (error) {
            console.error('Failed to save event to Kafka:', error);
            throw error;
        }
    }
    /**
     * イベントを取得
     */
    async getEvents(fromSequence = 0) {
        // バッファからイベントを取得
        return this.eventBuffer.filter(event => event.sequenceNumber >= fromSequence).sort((a, b) => a.sequenceNumber - b.sequenceNumber);
    }
    /**
     * イベントストリームを購読
     */
    subscribe(callback) {
        const subscription = this.eventSubject.subscribe(callback);
        return () => subscription.unsubscribe();
    }
    /**
     * イベントストリームをObservableとして取得
     */
    get events$() {
        return this.eventSubject.asObservable();
    }
    /**
     * パーティションを決定
     * イベントタイプに基づいてパーティショニング
     */
    getPartition(event) {
        // イベントタイプに基づいてパーティションを決定
        const hash = this.hashString(event.type);
        return hash % 3; // 3つのパーティションに分散
    }
    /**
     * 文字列をハッシュ化
     */
    hashString(str) {
        let hash = 0;
        for (let i = 0; i < str.length; i++) {
            const char = str.charCodeAt(i);
            hash = ((hash << 5) - hash) + char;
            hash = hash & hash; // 32bit整数に変換
        }
        return Math.abs(hash);
    }
    /**
     * 現在のシーケンス番号を取得
     */
    getCurrentSequenceNumber() {
        return this.sequenceNumber;
    }
    /**
     * バッファをクリア
     */
    clearBuffer() {
        this.eventBuffer = [];
    }
    /**
     * 接続状態を確認
     */
    isConnected() {
        return this.connected;
    }
    /**
     * 接続を切断
     */
    async disconnect() {
        try {
            await this.producer.disconnect();
            await this.consumer.disconnect();
            this.connected = false;
            this.eventSubject.complete();
            console.log('Kafka EventStore disconnected');
        }
        catch (error) {
            console.error('Failed to disconnect from Kafka:', error);
            throw error;
        }
    }
    /**
     * リソースを解放
     */
    async destroy() {
        await this.disconnect();
        this.eventBuffer = [];
    }
}
exports.KafkaEventStore = KafkaEventStore;
/**
 * インメモリイベントストア（開発・テスト用）
 */
class InMemoryEventStore {
    constructor() {
        this.events = [];
        this.eventSubject = new rxjs_1.Subject();
        this.sequenceNumber = 0;
    }
    /**
     * イベントを保存
     */
    async save(event) {
        event.sequenceNumber = ++this.sequenceNumber;
        this.events.push(event);
        this.eventSubject.next(event);
    }
    /**
     * イベントを取得
     */
    async getEvents(fromSequence = 0) {
        return this.events.filter(event => event.sequenceNumber >= fromSequence);
    }
    /**
     * イベントストリームを購読
     */
    subscribe(callback) {
        const subscription = this.eventSubject.subscribe(callback);
        return () => subscription.unsubscribe();
    }
    /**
     * イベントストリームをObservableとして取得
     */
    get events$() {
        return this.eventSubject.asObservable();
    }
    /**
     * 現在のシーケンス番号を取得
     */
    getCurrentSequenceNumber() {
        return this.sequenceNumber;
    }
    /**
     * 全てのイベントをクリア
     */
    clear() {
        this.events = [];
        this.sequenceNumber = 0;
    }
    /**
     * リソースを解放
     */
    destroy() {
        this.events = [];
        this.eventSubject.complete();
    }
}
exports.InMemoryEventStore = InMemoryEventStore;
//# sourceMappingURL=KafkaEventStore.js.map