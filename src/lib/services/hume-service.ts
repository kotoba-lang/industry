/**
 * Hume AIのAPIを利用して感情認識を行うサービス
 */
"use server"; // Enable Server Actions

// Humeの感情認識APIのレスポンス型
export interface HumeFaceEmotion {
  name: string;
  score: number;
}

export interface HumeFaceResponse {
  bbox?: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
  emotions: HumeFaceEmotion[];
  confidence?: number;
}

export interface HumeVoiceEmotion {
  name: string;
  score: number;
}

export interface HumeVoiceResponse {
  emotions: HumeVoiceEmotion[];
  confidence?: number;
  metadata?: {
    duration_ms?: number;
    speaking_rate?: number;
    pause_count?: number;
  };
}

// Server Actions for batch processing
export async function analyzeFace(
  imageBlob: Blob,
  apiKey: string
): Promise<HumeFaceResponse> {
  try {
    const formData = new FormData();
    formData.append('file', imageBlob, 'image.jpg');
    formData.append('json', JSON.stringify({
      models: {
        face: {}
      }
    }));
    
    const response = await fetch('https://api.hume.ai/v0/batch/jobs', {
      method: 'POST',
      headers: {
        'X-Hume-Api-Key': apiKey
      },
      body: formData
    });
    
    if (!response.ok) {
      throw new Error(`API error: ${response.status} ${response.statusText}`);
    }
    
    const jobResponse = await response.json();
    const jobId = jobResponse.job_id;
    
    // ジョブが完了するまで待機
    return await pollJobResults(jobId, apiKey);
  } catch (error) {
    console.error('Error analyzing face:', error);
    throw error;
  }
}

export async function analyzeVoice(
  audioBlob: Blob,
  apiKey: string
): Promise<HumeVoiceResponse> {
  try {
    const formData = new FormData();
    formData.append('file', audioBlob, 'audio.wav');
    formData.append('json', JSON.stringify({
      models: {
        prosody: {}
      }
    }));
    
    const response = await fetch('https://api.hume.ai/v0/batch/jobs', {
      method: 'POST',
      headers: {
        'X-Hume-Api-Key': apiKey
      },
      body: formData
    });
    
    if (!response.ok) {
      throw new Error(`API error: ${response.status} ${response.statusText}`);
    }
    
    const jobResponse = await response.json();
    const jobId = jobResponse.job_id;
    
    // ジョブが完了するまで待機
    return await pollJobResults(jobId, apiKey);
  } catch (error) {
    console.error('Error analyzing voice:', error);
    throw error;
  }
}

// Helper function for polling job results
async function pollJobResults(jobId: string, apiKey: string): Promise<any> {
  const maxAttempts = 30;
  const delayMs = 1000;
  
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    try {
      const response = await fetch(`https://api.hume.ai/v0/batch/jobs/${jobId}`, {
        method: 'GET',
        headers: {
          'X-Hume-Api-Key': apiKey
        }
      });
      
      if (!response.ok) {
        throw new Error(`API error: ${response.status} ${response.statusText}`);
      }
      
      const jobStatus = await response.json();
      
      if (jobStatus.status === 'COMPLETED') {
        // 結果を取得
        const predictionsResponse = await fetch(`https://api.hume.ai/v0/batch/jobs/${jobId}/predictions`, {
          method: 'GET',
          headers: {
            'X-Hume-Api-Key': apiKey,
            'accept': 'application/json; charset=utf-8'
          }
        });
        
        if (!predictionsResponse.ok) {
          throw new Error(`API error: ${predictionsResponse.status} ${predictionsResponse.statusText}`);
        }
        
        const predictions = await predictionsResponse.json();
        return predictions;
      } else if (jobStatus.status === 'FAILED') {
        throw new Error('Job failed');
      }
      
      // 一定時間待機
      await new Promise(resolve => setTimeout(resolve, delayMs));
    } catch (error) {
      console.error('Error polling job results:', error);
      throw error;
    }
  }
  
  throw new Error('Max polling attempts reached');
}

// WebSocketを使用したリアルタイム感情認識
export class HumeRealtimeEmotionService {
  private socket: WebSocket | null = null;
  private apiKey: string;
  private onFaceData: (data: HumeFaceResponse) => void;
  private onVoiceData: (data: HumeVoiceResponse) => void;
  private onError: (error: Event) => void;

  constructor(
    apiKey: string,
    onFaceData: (data: HumeFaceResponse) => void,
    onVoiceData: (data: HumeVoiceResponse) => void,
    onError: (error: Event) => void
  ) {
    this.apiKey = apiKey;
    this.onFaceData = onFaceData;
    this.onVoiceData = onVoiceData;
    this.onError = onError;
  }

  // WebSocketを初期化
  public async initWebSocket(models: string[] = ['face', 'prosody']): Promise<void> {
    if (this.socket) {
      this.socket.close();
    }

    try {
      const modelConfig: Record<string, Record<string, never>> = {};
      models.forEach(model => {
        modelConfig[model] = {};
      });

      // WebSocket接続を開始
      this.socket = new WebSocket('wss://api.hume.ai/v0/stream/models');
      
      this.socket.onopen = () => {
        if (this.socket) {
          // 認証と設定
          this.socket.send(JSON.stringify({
            type: 'configuration',
            apiKey: this.apiKey,
            models: modelConfig
          }));
        }
      };

      this.socket.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          
          if (data.type === 'prediction') {
            if (data.models.face) {
              this.onFaceData(data.models.face);
            }
            
            if (data.models.prosody) {
              this.onVoiceData(data.models.prosody);
            }
          }
        } catch (error) {
          console.error('Error parsing WebSocket message:', error);
        }
      };

      this.socket.onerror = this.onError;
      
      this.socket.onclose = () => {
        console.log('WebSocket connection closed');
      };
    } catch (error) {
      console.error('Error initializing WebSocket:', error);
      throw error;
    }
  }

  // 画像データを送信
  public async sendImageData(imageData: Blob): Promise<void> {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket connection not established');
    }

    try {
      const reader = new FileReader();
      reader.onloadend = () => {
        const base64data = reader.result as string;
        // Base64データからプレフィックスを削除
        const base64Content = base64data.split(',')[1];
        
        if (this.socket) {
          this.socket.send(JSON.stringify({
            type: 'frame',
            format: 'image/jpeg;base64',
            data: base64Content
          }));
        }
      };
      reader.readAsDataURL(imageData);
    } catch (error) {
      console.error('Error sending image data:', error);
      throw error;
    }
  }

  // 音声データを送信
  public async sendAudioData(audioData: Blob): Promise<void> {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket connection not established');
    }

    try {
      const reader = new FileReader();
      reader.onloadend = () => {
        const base64data = reader.result as string;
        // Base64データからプレフィックスを削除
        const base64Content = base64data.split(',')[1];
        
        if (this.socket) {
          this.socket.send(JSON.stringify({
            type: 'audio',
            format: 'audio/wav;base64',
            data: base64Content
          }));
        }
      };
      reader.readAsDataURL(audioData);
    } catch (error) {
      console.error('Error sending audio data:', error);
      throw error;
    }
  }

  // 接続を閉じる
  public closeConnection(): void {
    if (this.socket) {
      this.socket.close();
      this.socket = null;
    }
  }
} 