export type CorrelationLevel = 'とても近い' | '近い' | 'どちらでもない' | '遠い' | 'とても遠い';

export type ElementPair = {
  element1: string;
  element2: string;
};

export type CorrelationInput = {
  pair: ElementPair;
  level: CorrelationLevel;
};

export type Vector = number[];

export function correlationLevelToNumber(level: CorrelationLevel): number {
  switch (level) {
    case 'とても近い': return 1;
    case '近い': return 0.5;
    case 'どちらでもない': return 0;
    case '遠い': return -0.5;
    case 'とても遠い': return -1;
  }
}

