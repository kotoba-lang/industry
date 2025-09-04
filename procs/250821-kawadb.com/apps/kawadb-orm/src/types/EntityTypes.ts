/**
 * エンティティ関連の型定義
 */

// エンティティのコンストラクタ型
export type EntityConstructor<T = any> = new (...args: any[]) => T;

// エンティティのプロパティ型
export type EntityProperty<T> = {
  [K in keyof T]: T[K];
};

// カラム情報
export interface ColumnMetadata {
  name: string;
  type: DataType;
  nullable: boolean;
  primaryKey: boolean;
  autoIncrement: boolean;
  defaultValue?: any;
  unique?: boolean;
  index?: boolean;
}

// テーブル情報
export interface TableMetadata {
  name: string;
  columns: ColumnMetadata[];
  primaryKey: string[];
  indexes: IndexMetadata[];
}

// インデックス情報
export interface IndexMetadata {
  name: string;
  columns: string[];
  unique: boolean;
}

// データ型
export enum DataType {
  STRING = 'string',
  NUMBER = 'number',
  BOOLEAN = 'boolean',
  DATE = 'date',
  JSON = 'json',
  ARRAY = 'array',
  OBJECT = 'object'
}

// エンティティメタデータ
export interface EntityMetadata {
  target: EntityConstructor;
  name: string;
  tableName: string;
  columns: ColumnMetadata[];
  primaryKey: string[];
  relations: RelationMetadata[];
}

// リレーション情報
export interface RelationMetadata {
  type: 'one-to-one' | 'one-to-many' | 'many-to-one' | 'many-to-many';
  target: EntityConstructor;
  joinColumn?: string;
  inverseJoinColumn?: string;
  propertyName: string;
}

// エンティティのID型
export type EntityId = string | number;

// エンティティの基底クラス
export interface BaseEntity {
  id?: EntityId;
  createdAt?: Date;
  updatedAt?: Date;
}

// 検索条件
export interface FindOptions<T> {
  where?: Partial<T> | Partial<T>[];
  order?: { [P in keyof T]?: 'ASC' | 'DESC' };
  skip?: number;
  take?: number;
  relations?: string[];
}

// 更新条件
export interface UpdateOptions<T> {
  where: Partial<T>;
  data: Partial<T>;
}

// 削除条件
export interface DeleteOptions<T> {
  where: Partial<T>;
} 