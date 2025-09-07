/**
 * ロガー実装
 */

export enum LogLevel {
  ERROR = 0,
  WARN = 1,
  INFO = 2,
  DEBUG = 3,
  TRACE = 4
}

export interface LogEntry {
  level: LogLevel;
  message: string;
  timestamp: Date;
  data?: any;
  stack?: string;
}

export class Logger {
  private level: LogLevel;
  private prefix: string;
  private entries: LogEntry[] = [];
  private maxEntries: number = 1000;

  constructor(debugMode: boolean = false, prefix: string = 'KawaORM') {
    this.level = debugMode ? LogLevel.DEBUG : LogLevel.INFO;
    this.prefix = prefix;
  }

  setLevel(level: LogLevel): void {
    this.level = level;
  }

  error(message: string, data?: any): void {
    this.log(LogLevel.ERROR, message, data);
  }

  warn(message: string, data?: any): void {
    this.log(LogLevel.WARN, message, data);
  }

  info(message: string, data?: any): void {
    this.log(LogLevel.INFO, message, data);
  }

  debug(message: string, data?: any): void {
    this.log(LogLevel.DEBUG, message, data);
  }

  trace(message: string, data?: any): void {
    this.log(LogLevel.TRACE, message, data);
  }

  private log(level: LogLevel, message: string, data?: any): void {
    if (level > this.level) return;

    const entry: LogEntry = {
      level,
      message,
      timestamp: new Date(),
      data,
      stack: level === LogLevel.ERROR ? new Error().stack : undefined
    };

    this.entries.push(entry);
    
    // エントリ数の制限
    if (this.entries.length > this.maxEntries) {
      this.entries.shift();
    }

    // コンソールに出力
    this.outputToConsole(entry);
  }

  private outputToConsole(entry: LogEntry): void {
    const timestamp = entry.timestamp.toISOString();
    const levelName = LogLevel[entry.level];
    const prefix = `[${timestamp}] [${this.prefix}] [${levelName}]`;
    
    const message = `${prefix} ${entry.message}`;
    
    switch (entry.level) {
      case LogLevel.ERROR:
        console.error(message, entry.data || '', entry.stack || '');
        break;
      case LogLevel.WARN:
        console.warn(message, entry.data || '');
        break;
      case LogLevel.INFO:
        console.info(message, entry.data || '');
        break;
      case LogLevel.DEBUG:
      case LogLevel.TRACE:
        console.debug(message, entry.data || '');
        break;
    }
  }

  getLogs(level?: LogLevel): LogEntry[] {
    if (level !== undefined) {
      return this.entries.filter(entry => entry.level === level);
    }
    return [...this.entries];
  }

  clearLogs(): void {
    this.entries = [];
  }
} 