/**
 * KawaDB ORM - TypeScript ORM for KawaDB with KSQL support
 * 
 * Works in Web and Electron environments with WebAssembly backend
 */

// Core ORM exports
export * from './core/Entity';
export * from './core/Repository';
export * from './core/QueryBuilder';
export * from './core/Connection';
export * from './core/Schema';

// KSQL exports
export * from './ksql/KsqlEngine';
export * from './ksql/StreamBuilder';
export * from './ksql/TableBuilder';

// Decorators
export * from './decorators/Column';
export * from './decorators/Table';

// Types
export * from './types/DatabaseTypes';
export * from './types/KsqlTypes';
export * from './types/EntityTypes';

// Utilities
export * from './utils/WasmLoader';
export * from './utils/Environment';
export * from './utils/Logger';

// Main ORM class
export { KawaORM } from './KawaORM';

// Version
export const version = '0.1.0';

// Default export for convenience
export { KawaORM as default } from './KawaORM'; 