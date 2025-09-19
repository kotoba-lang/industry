// LLM-BOUNDARY: 20_ports - 抽象Port（ドメインが依存するだけ）

import { HumeEmotionResponse } from '@/00_schema';

export interface HumeApiPort {
  analyzeEmotions(videoBuffer: Buffer): Promise<HumeEmotionResponse>;
}

export interface ExternalApiPort {
  hume: HumeApiPort;
}
