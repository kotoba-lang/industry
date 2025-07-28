/**
 * サーバーサイドオーディオキャッシュユーティリティ
 * 
 * サーバー上に保存された音声ファイルを取得・保存するための関数を提供します。
 */

/**
 * サーバーキャッシュから音声を取得
 */
export async function getAudioFromServerCache(text: string, voice: string): Promise<Blob | null> {
  try {
    // URLエンコードしたパラメータでAPIを呼び出し
    const encodedText = encodeURIComponent(text);
    const encodedVoice = encodeURIComponent(voice);
    
    const response = await fetch(`/api/audio-cache?text=${encodedText}&voice=${encodedVoice}`, {
      method: 'GET',
      headers: {
        'Accept': 'audio/mp3',
      },
    });
    
    if (!response.ok) {
      // 404はキャッシュミス、それ以外はエラー
      if (response.status === 404) {
        return null;
      }
      throw new Error(`HTTP error: ${response.status}`);
    }
    
    // レスポンスをBlobとして取得
    const audioBlob = await response.blob();
    console.log(`Retrieved audio from server cache for: "${text}"`);
    return audioBlob;
  } catch (err) {
    console.error('Failed to retrieve audio from server cache:', err);
    return null;
  }
}

/**
 * 音声をサーバーキャッシュに保存
 */
export async function saveAudioToServerCache(text: string, voice: string, audioData: Blob): Promise<boolean> {
  try {
    // FormDataを作成して送信
    const formData = new FormData();
    formData.append('text', text);
    formData.append('voice', voice);
    formData.append('audio', audioData, 'audio.mp3');
    
    const response = await fetch('/api/audio-cache', {
      method: 'POST',
      body: formData,
    });
    
    if (!response.ok) {
      throw new Error(`HTTP error: ${response.status}`);
    }
    
    const result = await response.json();
    console.log(`Saved audio to server cache for: "${text}"`);
    return result.success === true;
  } catch (err) {
    console.error('Failed to save audio to server cache:', err);
    return false;
  }
}

/**
 * サーバーキャッシュを消去（古いエントリのみ、または全て）
 */
export async function clearServerAudioCache(olderThanDays?: number): Promise<boolean> {
  try {
    let url = '/api/audio-cache';
    if (olderThanDays !== undefined) {
      url += `?olderThanDays=${olderThanDays}`;
    }
    
    const response = await fetch(url, {
      method: 'DELETE',
    });
    
    if (!response.ok) {
      throw new Error(`HTTP error: ${response.status}`);
    }
    
    const result = await response.json();
    console.log(`Cleared server cache, deleted ${result.deletedCount} items`);
    return result.success === true;
  } catch (err) {
    console.error('Failed to clear server cache:', err);
    return false;
  }
}

/**
 * サーバーキャッシュのサイズを取得
 */
export async function getServerAudioCacheSize(): Promise<{ count: number, sizeBytes: number }> {
  try {
    const response = await fetch('/api/audio-cache', {
      method: 'PATCH',
    });
    
    if (!response.ok) {
      throw new Error(`HTTP error: ${response.status}`);
    }
    
    const result = await response.json();
    return {
      count: result.count,
      sizeBytes: result.sizeBytes
    };
  } catch (err) {
    console.error('Failed to get server cache size:', err);
    return { count: 0, sizeBytes: 0 };
  }
} 