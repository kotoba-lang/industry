// LLM-BOUNDARY: 50_adapters - RouteHandler/ServerActions/外部API実装

import { ExternalApiPort, HumeApiPort } from '@/20_ports';
import { HumeEmotionResponse } from '@/00_schema';
import { HumeClient } from 'hume';

class HumeApiAdapter implements HumeApiPort {
  private hume: HumeClient;

  constructor() {
    this.hume = new HumeClient({
      apiKey: process.env.HUME_API_KEY || '',
      secretKey: process.env.HUME_API || ''
    });
  }

  async analyzeEmotions(videoBuffer: Buffer): Promise<HumeEmotionResponse> {
    // 実際の実装ではHume APIを呼び出す
    // ここでは簡易的なモック
    return {
      predictions: [{
        emotions: [
          { name: 'joy', score: 0.8 },
          { name: 'surprise', score: 0.6 },
          { name: 'fear', score: 0.2 }
        ],
        confidence: 0.85
      }]
    };
  }
}

export class ExternalApiAdapter implements ExternalApiPort {
  hume: HumeApiAdapter;

  constructor() {
    this.hume = new HumeApiAdapter();
  }
}

export const externalApiAdapter = new ExternalApiAdapter();
