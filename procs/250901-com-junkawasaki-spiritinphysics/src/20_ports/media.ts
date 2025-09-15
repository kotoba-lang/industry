// LLM-BOUNDARY: 20_ports - 抽象Port（ドメインが依存するだけ）

export interface MediaPort {
  getUserMedia(constraints: MediaStreamConstraints): Promise<MediaStream>;
  recordAudio(stream: MediaStream, duration: number): Promise<Blob>;
  recordVideo(stream: MediaStream, duration: number): Promise<Blob>;
  stopMediaStream(stream: MediaStream): void;
}
