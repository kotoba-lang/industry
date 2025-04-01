declare module 'postgres' {
  import { Sql } from 'postgres';
  function postgres(url: string, config?: any): Sql;
  export = postgres;
} 