"use client";

import { HumeFaceResponse, HumeVoiceResponse } from "../actions/hume-service";

// WebSocket connection states for debugging
export enum ConnectionState {
  CLOSED = "CLOSED",
  CONNECTING = "CONNECTING",
  OPEN = "OPEN",
  AUTHENTICATED = "AUTHENTICATED",
  ERROR = "ERROR",
}

// WebSocketを使用したリアルタイム感情認識
export class HumeRealtimeEmotionService {
  private socket: WebSocket | null = null;
  private apiKey: string;
  private onFaceData: (data: HumeFaceResponse) => void;
  private onVoiceData: (data: HumeVoiceResponse) => void;
  private onError: (error: Event | Error) => void;
  private connectionState: ConnectionState = ConnectionState.CLOSED;
  private authenticationSent: boolean = false;
  private lastError: any = null;

  constructor(
    apiKey: string,
    onFaceData: (data: HumeFaceResponse) => void,
    onVoiceData: (data: HumeVoiceResponse) => void,
    onError: (error: Event | Error) => void,
  ) {
    this.apiKey = apiKey;
    this.onFaceData = onFaceData;
    this.onVoiceData = onVoiceData;
    this.onError = onError;

    // Validate API key format
    if (!apiKey || apiKey.trim() === "") {
      this.handleError(new Error("API key is empty or undefined"));
    } else if (apiKey.length < 20) {
      this.handleError(
        new Error("API key appears to be too short (< 20 chars)"),
      );
    }
  }

  // WebSocketを初期化
  public async initWebSocket(
    models: string[] = ["face", "prosody"],
  ): Promise<void> {
    if (this.socket) {
      console.log(
        "Closing existing WebSocket connection before creating a new one",
      );
      this.socket.close();
    }

    try {
      const modelConfig: Record<string, Record<string, never>> = {};
      models.forEach((model) => {
        modelConfig[model] = {};
      });

      this.connectionState = ConnectionState.CONNECTING;
      console.log("Initializing WebSocket connection to Hume API...");

      // WebSocket接続を開始
      this.socket = new WebSocket("wss://api.hume.ai/v0/stream/models");

      this.socket.onopen = () => {
        if (this.socket) {
          this.connectionState = ConnectionState.OPEN;
          console.log(
            "WebSocket connection established. Sending authentication...",
          );

          // 認証と設定
          try {
            const configPayload = {
              type: "configuration",
              apiKey: this.apiKey,
              models: modelConfig,
            };

            this.socket.send(JSON.stringify(configPayload));
            this.authenticationSent = true;
            console.log(
              "Authentication payload sent with models:",
              Object.keys(modelConfig).join(", "),
            );
          } catch (error) {
            this.handleError(
              new Error(`Failed to send authentication: ${error}`),
            );
          }
        }
      };

      this.socket.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);

          // Check for authentication confirmation
          if (data.type === "configuration") {
            this.connectionState = ConnectionState.AUTHENTICATED;
            console.log("Authentication with Hume API successful");
          } // Check for errors from server
          else if (data.type === "error") {
            const errorMessage = data.message || "Unknown server error";
            this.handleError(
              new Error(`Hume API server error: ${errorMessage}`),
            );
            return;
          } // Process predictions
          else if (data.type === "prediction") {
            if (data.models?.face) {
              this.onFaceData(data.models.face);
            }

            if (data.models?.prosody) {
              this.onVoiceData(data.models.prosody);
            }
          }
        } catch (error) {
          console.error("Error parsing WebSocket message:", error);
        }
      };

      this.socket.onerror = (event) => {
        this.connectionState = ConnectionState.ERROR;
        this.lastError = event;

        // Get more information if possible
        let errorInfo = "(No details available)";
        if (event instanceof ErrorEvent) {
          errorInfo = event.message;
        }

        // Try to get network status information
        const networkInfo = {
          online: typeof navigator !== "undefined"
            ? navigator.onLine
            : "unknown",
          protocol: typeof window !== "undefined"
            ? window.location.protocol
            : "unknown",
        };

        console.error(`WebSocket connection error: ${errorInfo}`, {
          networkStatus: networkInfo,
          readyState: this.socket ? this.socket.readyState : "no socket",
          apiKeyLength: this.apiKey ? this.apiKey.length : 0,
        });

        this.handleError({
          ...event,
          additionalInfo: {
            message: errorInfo,
            networkStatus: networkInfo,
          },
        });
      };

      this.socket.onclose = (event) => {
        this.connectionState = ConnectionState.CLOSED;
        this.authenticationSent = false;

        // Check for authentication failure
        if (this.authenticationSent && !event.wasClean) {
          this.handleError(
            new Error(
              `WebSocket connection closed unexpectedly. Code: ${event.code}, Reason: ${
                event.reason || "Unknown reason"
              }`,
            ),
          );
          console.error(
            "Connection might have been closed due to authentication failure. Please check API key validity.",
          );
        } else {
          console.log(
            `WebSocket connection closed. Code: ${event.code}, Reason: ${
              event.reason || "No reason provided"
            }`,
            event.wasClean ? "Clean close" : "Unclean close",
          );
        }
      };
    } catch (error) {
      this.connectionState = ConnectionState.ERROR;
      this.lastError = error;
      console.error("Error initializing WebSocket:", error);
      this.handleError(error);
      throw error;
    }
  }

  // Helper method to handle errors in a consistent way
  private handleError(error: Event | Error | unknown): void {
    this.lastError = error;
    this.connectionState = ConnectionState.ERROR;
    this.onError(error);
  }

  // 画像データを送信
  public async sendImageData(imageData: Blob): Promise<void> {
    if (!this.socket) {
      this.handleError(new Error("WebSocket connection not initialized"));
      return;
    }

    if (this.socket.readyState !== WebSocket.OPEN) {
      const stateMap = {
        [WebSocket.CONNECTING]: "connecting",
        [WebSocket.CLOSING]: "closing",
        [WebSocket.CLOSED]: "closed",
      };
      this.handleError(
        new Error(
          `Cannot send data: WebSocket is ${
            stateMap[this.socket.readyState as keyof typeof stateMap] ||
            "in unknown state"
          }`,
        ),
      );
      return;
    }

    try {
      const reader = new FileReader();
      reader.onloadend = () => {
        const base64data = reader.result as string;
        // Base64データからプレフィックスを削除
        const base64Content = base64data.split(",")[1];

        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
          try {
            this.socket.send(JSON.stringify({
              type: "frame",
              format: "image/jpeg;base64",
              data: base64Content,
            }));
          } catch (sendError) {
            this.handleError(
              new Error(`Error sending frame data to WebSocket: ${sendError}`),
            );
          }
        }
      };

      reader.onerror = (fileError) => {
        this.handleError(new Error(`Error reading image data: ${fileError}`));
      };

      reader.readAsDataURL(imageData);
    } catch (error) {
      this.handleError(new Error(`Error preparing image data: ${error}`));
    }
  }

  // 音声データを送信
  public async sendAudioData(audioData: Blob): Promise<void> {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      this.handleError(new Error("WebSocket connection not established"));
      return;
    }

    try {
      const reader = new FileReader();
      reader.onloadend = () => {
        const base64data = reader.result as string;
        // Base64データからプレフィックスを削除
        const base64Content = base64data.split(",")[1];

        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
          this.socket.send(JSON.stringify({
            type: "audio",
            format: "audio/wav;base64",
            data: base64Content,
          }));
        }
      };
      reader.readAsDataURL(audioData);
    } catch (error) {
      this.handleError(new Error(`Error sending audio data: ${error}`));
    }
  }

  // Get current connection state (for debugging)
  public getConnectionState(): ConnectionState {
    return this.connectionState;
  }

  // Get last error for debugging
  public getLastError(): any {
    return this.lastError;
  }

  // Check if WebSocket is connected
  public isConnected(): boolean {
    return this.socket !== null && this.socket.readyState === WebSocket.OPEN;
  }

  // Check if authentication was completed
  public isAuthenticated(): boolean {
    return this.connectionState === ConnectionState.AUTHENTICATED;
  }

  // 接続を閉じる
  public closeConnection(): void {
    if (this.socket) {
      this.socket.close();
      this.socket = null;
      this.connectionState = ConnectionState.CLOSED;
      this.authenticationSent = false;
    }
  }
}
