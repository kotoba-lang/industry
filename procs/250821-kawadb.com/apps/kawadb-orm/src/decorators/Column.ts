/**
 * カラムデコレータ
 */

import { ColumnMetadata, DataType } from '../types/EntityTypes';
import { getEntityMetadata } from './Entity';

interface ColumnOptions {
  type?: DataType | string;
  nullable?: boolean;
  primaryKey?: boolean;
  autoIncrement?: boolean;
  defaultValue?: any;
  unique?: boolean;
  index?: boolean;
}

/**
 * カラムデコレータ
 */
export function Column(options: ColumnOptions = {}) {
  return function (target: any, propertyKey: string) {
    const constructor = target.constructor;
    const metadata = getEntityMetadata(constructor);
    
    if (metadata) {
      const columnMetadata: ColumnMetadata = {
        name: propertyKey,
        type: parseDataType(options.type),
        nullable: options.nullable ?? true,
        primaryKey: options.primaryKey ?? false,
        autoIncrement: options.autoIncrement ?? false,
        defaultValue: options.defaultValue,
        unique: options.unique ?? false,
        index: options.index ?? false
      };
      
      metadata.columns.push(columnMetadata);
      
      // プライマリキーの場合は配列に追加
      if (columnMetadata.primaryKey) {
        metadata.primaryKey.push(propertyKey);
      }
    }
  };
}

/**
 * プライマリキーデコレータ
 */
export function PrimaryKey() {
  return function (target: any, propertyKey: string) {
    Column({ primaryKey: true, nullable: false })(target, propertyKey);
  };
}

/**
 * 自動インクリメントデコレータ
 */
export function AutoIncrement() {
  return function (target: any, propertyKey: string) {
    Column({ autoIncrement: true, primaryKey: true, nullable: false })(target, propertyKey);
  };
}

/**
 * ユニークデコレータ
 */
export function Unique() {
  return function (target: any, propertyKey: string) {
    Column({ unique: true })(target, propertyKey);
  };
}

/**
 * インデックスデコレータ
 */
export function Index() {
  return function (target: any, propertyKey: string) {
    Column({ index: true })(target, propertyKey);
  };
}

/**
 * データ型を解析
 */
function parseDataType(type?: DataType | string): DataType {
  if (!type) {
    return DataType.STRING;
  }
  
  if (typeof type === 'string') {
    switch (type.toLowerCase()) {
      case 'string':
      case 'text':
        return DataType.STRING;
      case 'number':
      case 'int':
      case 'integer':
        return DataType.NUMBER;
      case 'boolean':
      case 'bool':
        return DataType.BOOLEAN;
      case 'date':
      case 'datetime':
        return DataType.DATE;
      case 'json':
      case 'object':
        return DataType.JSON;
      case 'array':
        return DataType.ARRAY;
      default:
        return DataType.STRING;
    }
  }
  
  return type;
} 