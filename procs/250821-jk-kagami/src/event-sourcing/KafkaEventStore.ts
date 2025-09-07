import { Kafka, Producer, Consumer, KafkaMessage } from 'kafkajs';
import { Subject, Observable } from 'rxjs';
import { 
  EventStore, 
  KagamiEvent, 
  KafkaConfig 
} from '@/types';

/**
 * Kafkaベースのイベントストア
 * イベントソーシングのSSOT（Single Source of Truth）として機能
 */
export class KafkaEventStore implements EventStore {
  private kafka: Kafka;
  private producer: Producer;
  private consumer: Consumer;
  private eventSubject = new Subject<KagamiEvent>();
  private connected = false;
  private eventBuffer: KagamiEvent[] = [];
  private sequenceNumber = 0;

  constructor(private config: KafkaConfig) {
    const kafkaOptions: any = {
      clientId: config.clientId,
      brokers: config.brokers,
    };

    if (config.ssl) {
      kafkaOptions.ssl = config.ssl;
    }

    if (config.sasl) {
      kafkaOptions.sasl = config.sasl;
    }

    this.kafka = new Kafka(kafkaOptions);

    this.producer = this.kafka.producer();
    this.consumer = this.kafka.consumer({ 
      groupId: config.groupId || `kagami-${Date.now()}` 
    });
  }

  /**
   * Kafkaに接続
   */
  public async connect(): Promise<void> {
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
      
    } catch (error) {
      console.error('Failed to connect to Kafka:', error);
      throw error;
    }
  }

  /**
   * Kafkaメッセージを処理
   */
  private async handleKafkaMessage(message: KafkaMessage): Promise<void> {
    try {
      if (!message.value) return;

      const eventData = JSON.parse(message.value.toString());
      const event: KagamiEvent = {
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
      
    } catch (error) {
      console.error('Failed to process Kafka message:', error);
    }
  }

  /**
   * イベントを保存
   */
  public async save(event: KagamiEvent): Promise<void> {
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
      
    } catch (error) {
      console.error('Failed to save event to Kafka:', error);
      throw error;
    }
  }

  /**
   * イベントを取得
   */
  public async getEvents(fromSequence: number = 0): Promise<KagamiEvent[]> {
    // バッファからイベントを取得
    return this.eventBuffer.filter(event => 
      event.sequenceNumber >= fromSequence
    ).sort((a, b) => a.sequenceNumber - b.sequenceNumber);
  }

  /**
   * イベントストリームを購読
   */
  public subscribe(callback: (event: KagamiEvent) => void): () => void {
    const subscription = this.eventSubject.subscribe(callback);
    return () => subscription.unsubscribe();
  }

  /**
   * イベントストリームをObservableとして取得
   */
  public get events$(): Observable<KagamiEvent> {
    return this.eventSubject.asObservable();
  }

  /**
   * パーティションを決定
   * イベントタイプに基づいてパーティショニング
   */
  private getPartition(event: KagamiEvent): number {
    // イベントタイプに基づいてパーティションを決定
    const hash = this.hashString(event.type);
    return hash % 3; // 3つのパーティションに分散
  }

  /**
   * 文字列をハッシュ化
   */
  private hashString(str: string): number {
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
  public getCurrentSequenceNumber(): number {
    return this.sequenceNumber;
  }

  /**
   * バッファをクリア
   */
  public clearBuffer(): void {
    this.eventBuffer = [];
  }

  /**
   * 接続状態を確認
   */
  public isConnected(): boolean {
    return this.connected;
  }

  /**
   * 接続を切断
   */
  public async disconnect(): Promise<void> {
    try {
      await this.producer.disconnect();
      await this.consumer.disconnect();
      this.connected = false;
      this.eventSubject.complete();
      console.log('Kafka EventStore disconnected');
    } catch (error) {
      console.error('Failed to disconnect from Kafka:', error);
      throw error;
    }
  }

  /**
   * リソースを解放
   */
  public async destroy(): Promise<void> {
    await this.disconnect();
    this.eventBuffer = [];
  }
}

/**
 * インメモリイベントストア（開発・テスト用）
 */
export class InMemoryEventStore implements EventStore {
  private events: KagamiEvent[] = [];
  private eventSubject = new Subject<KagamiEvent>();
  private sequenceNumber = 0;

  /**
   * イベントを保存
   */
  public async save(event: KagamiEvent): Promise<void> {
    event.sequenceNumber = ++this.sequenceNumber;
    this.events.push(event);
    this.eventSubject.next(event);
  }

  /**
   * イベントを取得
   */
  public async getEvents(fromSequence: number = 0): Promise<KagamiEvent[]> {
    return this.events.filter(event => 
      event.sequenceNumber >= fromSequence
    );
  }

  /**
   * イベントストリームを購読
   */
  public subscribe(callback: (event: KagamiEvent) => void): () => void {
    const subscription = this.eventSubject.subscribe(callback);
    return () => subscription.unsubscribe();
  }

  /**
   * イベントストリームをObservableとして取得
   */
  public get events$(): Observable<KagamiEvent> {
    return this.eventSubject.asObservable();
  }

  /**
   * 現在のシーケンス番号を取得
   */
  public getCurrentSequenceNumber(): number {
    return this.sequenceNumber;
  }

  /**
   * 全てのイベントをクリア
   */
  public clear(): void {
    this.events = [];
    this.sequenceNumber = 0;
  }

  /**
   * リソースを解放
   */
  public destroy(): void {
    this.events = [];
    this.eventSubject.complete();
  }
} 