'use client'

import { HumeFaceResponse, HumeVoiceResponse } from '../actions/hume-service';

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