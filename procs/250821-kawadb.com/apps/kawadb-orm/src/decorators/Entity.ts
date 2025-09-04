/**
 * エンティティデコレータ
 */

import { EntityMetadata } from '../types/EntityTypes';

const entityMetadataMap = new Map<Function, EntityMetadata>();

/**
 * エンティティデコレータ
 */
export function Entity(name?: string) {
  return function <T extends { new (...args: any[]): {} }>(constructor: T) {
    const entityName = name || constructor.name;
    
    const metadata: EntityMetadata = {
      target: constructor,
      name: entityName,
      tableName: entityName.toLowerCase(),
      columns: [],
      primaryKey: [],
      relations: []
    };
    
    entityMetadataMap.set(constructor, metadata);
    
    return constructor;
  };
}

/**
 * エンティティメタデータを取得
 */
export function getEntityMetadata(target: Function): EntityMetadata | undefined {
  return entityMetadataMap.get(target);
}

/**
 * 全エンティティメタデータを取得
 */
export function getAllEntityMetadata(): EntityMetadata[] {
  return Array.from(entityMetadataMap.values());
} 