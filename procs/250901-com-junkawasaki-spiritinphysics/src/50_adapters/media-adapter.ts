// LLM-BOUNDARY: 50_adapters - RouteHandler/ServerActions/外部API実装

import { MediaPort } from '@/20_ports';

export class MediaAdapter implements MediaPort {
  async getUserMedia(constraints: MediaStreamConstraints): Promise<MediaStream> {
    return navigator.mediaDevices.getUserMedia(constraints);
  }

  async recordAudio(stream: MediaStream, duration: number): Promise<Blob> {
    return new Promise((resolve, reject) => {
      const mediaRecorder = new MediaRecorder(stream);
      const chunks: Blob[] = [];

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          chunks.push(event.data);
        }
      };

      mediaRecorder.onstop = () => {
        const blob = new Blob(chunks, { type: 'audio/webm' });
        resolve(blob);
      };

      mediaRecorder.onerror = (event) => {
        reject(new Error('Media recording failed'));
      };

      mediaRecorder.start();

      setTimeout(() => {
        mediaRecorder.stop();
      }, duration);
    });
  }

  async recordVideo(stream: MediaStream, duration: number): Promise<Blob> {
    return new Promise((resolve, reject) => {
      const mediaRecorder = new MediaRecorder(stream, {
        mimeType: 'video/webm;codecs=vp9'
      });
      const chunks: Blob[] = [];

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          chunks.push(event.data);
        }
      };

      mediaRecorder.onstop = () => {
        const blob = new Blob(chunks, { type: 'video/webm' });
        resolve(blob);
      };

      mediaRecorder.onerror = (event) => {
        reject(new Error('Video recording failed'));
      };

      mediaRecorder.start();

      setTimeout(() => {
        mediaRecorder.stop();
      }, duration);
    });
  }

  stopMediaStream(stream: MediaStream): void {
    stream.getTracks().forEach(track => track.stop());
  }
}

export const mediaAdapter = new MediaAdapter();
