/**
 * KSQL ストリームビルダー
 */

import { KsqlStream, KsqlColumn, KsqlDataType, KsqlDataFormat, KsqlWindow } from '../types/KsqlTypes';

export class StreamBuilder {
  private stream: Partial<KsqlStream> = {};

  /**
   * ストリーム名を設定
   */
  name(streamName: string): StreamBuilder {
    this.stream.name = streamName;
    return this;
  }

  /**
   * カラムを追加
   */
  addColumn(name: string, type: KsqlDataType, nullable: boolean = true): StreamBuilder {
    if (!this.stream.columns) {
      this.stream.columns = [];
    }
    
    this.stream.columns.push({
      name,
      type,
      nullable
    });
    
    return this;
  }

  /**
   * 複数のカラムを追加
   */
  columns(columns: KsqlColumn[]): StreamBuilder {
    this.stream.columns = columns;
    return this;
  }

  /**
   * Kafkaトピックを設定
   */
  topic(topicName: string): StreamBuilder {
    this.stream.topic = topicName;
    return this;
  }

  /**
   * データフォーマットを設定
   */
  format(dataFormat: KsqlDataFormat): StreamBuilder {
    this.stream.format = dataFormat;
    return this;
  }

  /**
   * パーティション設定
   */
  partitionBy(field: string): StreamBuilder {
    this.stream.partitionBy = field;
    return this;
  }

  /**
   * ウィンドウ設定
   */
  window(window: KsqlWindow): StreamBuilder {
    this.stream.window = window;
    return this;
  }

  /**
   * タンブリングウィンドウを設定
   */
  tumblingWindow(size: number, unit: 'seconds' | 'minutes' | 'hours' | 'days'): StreamBuilder {
    this.stream.window = {
      type: 'tumbling',
      size,
      unit
    };
    return this;
  }

  /**
   * ホッピングウィンドウを設定
   */
  hoppingWindow(
    size: number, 
    advance: number, 
    unit: 'seconds' | 'minutes' | 'hours' | 'days'
  ): StreamBuilder {
    this.stream.window = {
      type: 'hopping',
      size,
      advance,
      unit
    };
    return this;
  }

  /**
   * セッションウィンドウを設定
   */
  sessionWindow(size: number, unit: 'seconds' | 'minutes' | 'hours' | 'days'): StreamBuilder {
    this.stream.window = {
      type: 'session',
      size,
      unit
    };
    return this;
  }

  /**
   * ストリーム設定を構築
   */
  build(): KsqlStream {
    if (!this.stream.name) {
      throw new Error('Stream name is required');
    }
    
    if (!this.stream.columns || this.stream.columns.length === 0) {
      throw new Error('Stream must have at least one column');
    }
    
    if (!this.stream.topic) {
      throw new Error('Kafka topic is required');
    }
    
    if (!this.stream.format) {
      this.stream.format = KsqlDataFormat.JSON;
    }

    return this.stream as KsqlStream;
  }

  /**
   * CREATE STREAM SQLを生成
   */
  toSQL(): string {
    const stream = this.build();
    
    const columns = stream.columns.map(col => 
      `${col.name} ${col.type}${col.nullable ? '' : ' NOT NULL'}`
    ).join(', ');
    
    let sql = `CREATE STREAM ${stream.name} (${columns}) WITH (`;
    sql += `KAFKA_TOPIC='${stream.topic}', VALUE_FORMAT='${stream.format}'`;
    
    if (stream.partitionBy) {
      sql += `, PARTITIONS=${stream.partitionBy}`;
    }
    
    sql += ')';
    
    return sql;
  }

  /**
   * ビルダーをリセット
   */
  reset(): StreamBuilder {
    this.stream = {};
    return this;
  }

  /**
   * ビルダーを複製
   */
  clone(): StreamBuilder {
    const cloned = new StreamBuilder();
    cloned.stream = JSON.parse(JSON.stringify(this.stream));
    return cloned;
  }

  /**
   * よく使われるカラム型のヘルパーメソッド
   */
  stringColumn(name: string, nullable: boolean = true): StreamBuilder {
    return this.addColumn(name, KsqlDataType.STRING, nullable);
  }

  integerColumn(name: string, nullable: boolean = true): StreamBuilder {
    return this.addColumn(name, KsqlDataType.INTEGER, nullable);
  }

  bigintColumn(name: string, nullable: boolean = true): StreamBuilder {
    return this.addColumn(name, KsqlDataType.BIGINT, nullable);
  }

  doubleColumn(name: string, nullable: boolean = true): StreamBuilder {
    return this.addColumn(name, KsqlDataType.DOUBLE, nullable);
  }

  booleanColumn(name: string, nullable: boolean = true): StreamBuilder {
    return this.addColumn(name, KsqlDataType.BOOLEAN, nullable);
  }

  timestampColumn(name: string, nullable: boolean = true): StreamBuilder {
    return this.addColumn(name, KsqlDataType.TIMESTAMP, nullable);
  }

  arrayColumn(name: string, nullable: boolean = true): StreamBuilder {
    return this.addColumn(name, KsqlDataType.ARRAY, nullable);
  }

  mapColumn(name: string, nullable: boolean = true): StreamBuilder {
    return this.addColumn(name, KsqlDataType.MAP, nullable);
  }

  structColumn(name: string, nullable: boolean = true): StreamBuilder {
    return this.addColumn(name, KsqlDataType.STRUCT, nullable);
  }
} 