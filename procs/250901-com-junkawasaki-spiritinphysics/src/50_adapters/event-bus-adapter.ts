// LLM-BOUNDARY: 50_adapters - RouteHandler/ServerActions/外部API実装

import { EventBusPort, DomainEvent } from '@/20_ports';
import { EventType } from '@/10_events';

export class EventBusAdapter implements EventBusPort {
  private subscribers: Map<EventType, Array<(event: DomainEvent) => void>> = new Map();

  async publish(event: DomainEvent): Promise<void> {
    console.log('Publishing event:', event);

    const handlers = this.subscribers.get(event.type) || [];
    handlers.forEach(handler => {
      try {
        handler(event);
      } catch (error) {
        console.error('Error in event handler:', error);
      }
    });
  }

  subscribe(eventType: EventType, handler: (event: DomainEvent) => void): () => void {
    if (!this.subscribers.has(eventType)) {
      this.subscribers.set(eventType, []);
    }

    this.subscribers.get(eventType)!.push(handler);

    // 購読解除関数を返す
    return () => {
      const handlers = this.subscribers.get(eventType) || [];
      const index = handlers.indexOf(handler);
      if (index > -1) {
        handlers.splice(index, 1);
      }
    };
  }
}

export const eventBusAdapter = new EventBusAdapter();
