/**
 * テーブルデコレータ
 */

import { getEntityMetadata } from './Entity';

/**
 * テーブルデコレータ
 */
export function Table(tableName: string) {
  return function <T extends { new (...args: any[]): {} }>(constructor: T) {
    const metadata = getEntityMetadata(constructor);
    if (metadata) {
      metadata.tableName = tableName;
    }
    
    return constructor;
  };
} 