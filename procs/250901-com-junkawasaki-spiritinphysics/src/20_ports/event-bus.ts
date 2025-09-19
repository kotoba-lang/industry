// LLM-BOUNDARY: 20_ports - 抽象Port（ドメインが依存するだけ）

import { EventType } from '@/10_events';

export interface DomainEvent {
  type: EventType;
  payload?: any;
  timestamp: number;
  aggregateId?: string;
}

export interface EventBusPort {
  publish(event: DomainEvent): Promise<void>;
  subscribe(eventType: EventType, handler: (event: DomainEvent) => void): () => void;
}
