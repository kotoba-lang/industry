/**
 * KSQL テーブルビルダー
 */

import { KsqlTable, KsqlColumn, KsqlDataType, KsqlDataFormat, KsqlWindow } from '../types/KsqlTypes';

export class TableBuilder {
  private table: Partial<KsqlTable> = {};

  /**
   * テーブル名を設定
   */
  name(tableName: string): TableBuilder {
    this.table.name = tableName;
    return this;
  }

  /**
   * カラムを追加
   */
  addColumn(name: string, type: KsqlDataType, nullable: boolean = true): TableBuilder {
    if (!this.table.columns) {
      this.table.columns = [];
    }
    
    this.table.columns.push({
      name,
      type,
      nullable
    });
    
    return this;
  }

  /**
   * 複数のカラムを追加
   */
  columns(columns: KsqlColumn[]): TableBuilder {
    this.table.columns = columns;
    return this;
  }

  /**
   * Kafkaトピックを設定
   */
  topic(topicName: string): TableBuilder {
    this.table.topic = topicName;
    return this;
  }

  /**
   * データフォーマットを設定
   */
  format(dataFormat: KsqlDataFormat): TableBuilder {
    this.table.format = dataFormat;
    return this;
  }

  /**
   * プライマリキーを設定
   */
  primaryKey(...keys: string[]): TableBuilder {
    this.table.primaryKey = keys;
    return this;
  }

  /**
   * ウィンドウ設定
   */
  window(window: KsqlWindow): TableBuilder {
    this.table.window = window;
    return this;
  }

  /**
   * タンブリングウィンドウを設定
   */
  tumblingWindow(size: number, unit: 'seconds' | 'minutes' | 'hours' | 'days'): TableBuilder {
    this.table.window = {
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
  ): TableBuilder {
    this.table.window = {
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
  sessionWindow(size: number, unit: 'seconds' | 'minutes' | 'hours' | 'days'): TableBuilder {
    this.table.window = {
      type: 'session',
      size,
      unit
    };
    return this;
  }

  /**
   * テーブル設定を構築
   */
  build(): KsqlTable {
    if (!this.table.name) {
      throw new Error('Table name is required');
    }
    
    if (!this.table.columns || this.table.columns.length === 0) {
      throw new Error('Table must have at least one column');
    }
    
    if (!this.table.topic) {
      throw new Error('Kafka topic is required');
    }
    
    if (!this.table.format) {
      this.table.format = KsqlDataFormat.JSON;
    }

    if (!this.table.primaryKey || this.table.primaryKey.length === 0) {
      throw new Error('Table must have at least one primary key');
    }

    return this.table as KsqlTable;
  }

  /**
   * CREATE TABLE SQLを生成
   */
  toSQL(): string {
    const table = this.build();
    
    const columns = table.columns.map(col => 
      `${col.name} ${col.type}${col.nullable ? '' : ' NOT NULL'}`
    ).join(', ');
    
    let sql = `CREATE TABLE ${table.name} (${columns}) WITH (`;
    sql += `KAFKA_TOPIC='${table.topic}', VALUE_FORMAT='${table.format}'`;
    sql += `, KEY='${table.primaryKey.join(',')}'`;
    sql += ')';
    
    return sql;
  }

  /**
   * ビルダーをリセット
   */
  reset(): TableBuilder {
    this.table = {};
    return this;
  }

  /**
   * ビルダーを複製
   */
  clone(): TableBuilder {
    const cloned = new TableBuilder();
    cloned.table = JSON.parse(JSON.stringify(this.table));
    return cloned;
  }

  /**
   * よく使われるカラム型のヘルパーメソッド
   */
  stringColumn(name: string, nullable: boolean = true): TableBuilder {
    return this.addColumn(name, KsqlDataType.STRING, nullable);
  }

  integerColumn(name: string, nullable: boolean = true): TableBuilder {
    return this.addColumn(name, KsqlDataType.INTEGER, nullable);
  }

  bigintColumn(name: string, nullable: boolean = true): TableBuilder {
    return this.addColumn(name, KsqlDataType.BIGINT, nullable);
  }

  doubleColumn(name: string, nullable: boolean = true): TableBuilder {
    return this.addColumn(name, KsqlDataType.DOUBLE, nullable);
  }

  booleanColumn(name: string, nullable: boolean = true): TableBuilder {
    return this.addColumn(name, KsqlDataType.BOOLEAN, nullable);
  }

  timestampColumn(name: string, nullable: boolean = true): TableBuilder {
    return this.addColumn(name, KsqlDataType.TIMESTAMP, nullable);
  }

  arrayColumn(name: string, nullable: boolean = true): TableBuilder {
    return this.addColumn(name, KsqlDataType.ARRAY, nullable);
  }

  mapColumn(name: string, nullable: boolean = true): TableBuilder {
    return this.addColumn(name, KsqlDataType.MAP, nullable);
  }

  structColumn(name: string, nullable: boolean = true): TableBuilder {
    return this.addColumn(name, KsqlDataType.STRUCT, nullable);
  }

  /**
   * 単一プライマリキー設定のヘルパー
   */
  singlePrimaryKey(key: string): TableBuilder {
    return this.primaryKey(key);
  }

  /**
   * 複合プライマリキー設定のヘルパー
   */
  compositePrimaryKey(...keys: string[]): TableBuilder {
    return this.primaryKey(...keys);
  }
} 