import { readFileSync, readdirSync, existsSync, statSync } from 'fs';
import { join } from 'path';

// サーバーサイドでのみインポート
let kuzuManager: any = null;
let Participant: any, Session: any, VideoFile: any;
let blobStorage: any = null;

if (typeof window === 'undefined') {
  try {
    // ビルド時のエラーを避けるために条件付きで import
    const kuzuAvailable = process.env.KUZU_AVAILABLE === 'true';
    if (kuzuAvailable) {
      const kuzuModule = require('./database/kuzu-manager');
      kuzuManager = new kuzuModule.KuzuManager();
      Participant = kuzuModule.Participant;
      Session = kuzuModule.Session;
      VideoFile = kuzuModule.VideoFile;
    }
  } catch (error) {
    console.warn('Kuzu manager not available:', error);
  }

  try {
    const blobModule = require('./blob-storage');
    blobStorage = blobModule.BlobStorage;
  } catch (error) {
    console.warn('Blob storage not available:', error);
  }
}

const ARTIFACTS_CACHE_PATH = '/Users/junkawasaki/jun784/root/procs/250901-com-junkawasaki-spiritinphysics/.artifacts_cache';

// Kuzu初期化関数
export async function initializeKuzuDatabase(): Promise<void> {
  try {
    if (kuzuManager) {
      // KuzuManagerはコンストラクタで既に初期化されているため、
      // ここでは何もしないか、必要に応じて再初期化
      console.log('Kuzu database manager is available');
    } else {
      console.warn('Kuzu database manager is not available');
    }
  } catch (error) {
    console.error('Failed to initialize Kuzu database:', error);
  }
}

// Types based on actual data structure
export interface ConsentData {
  participantId: string;
  signature: string;
  agreements?: {
    understand: boolean;
    voluntary: boolean;
    withdraw: boolean;
    recording: boolean;
  };
  agreedAt: string;
}

export interface SessionEvent {
  timestamp: number;
  type: string;
  payload: Record<string, unknown>;
}

export interface SessionData {
  participantId: string;
  events: SessionEvent[];
  wordResponses: unknown[];
}

export interface Participant {
  id: string;
  signature: string;
  agreedAt: Date;
  hasSessionData: boolean;
  hasVideoFiles: boolean;
  videoFiles: string[];
}

// Parse database.jsonl file
export async function loadConsentDataFromDatabase(): Promise<ConsentData[]> {
  try {
    // まずBlobからデータを取得しようとする
    if (blobStorage) {
      try {
        const participantIds = await blobStorage.getAllParticipants();
        const consentData: ConsentData[] = [];

        for (const participantId of participantIds) {
          try {
            // Check if participant exists before attempting to download
            const exists = await blobStorage.participantExists(participantId);
            if (!exists) {
              console.warn(`Participant ${participantId} does not exist in Blob storage, skipping`);
              continue;
            }
            const participantData = await blobStorage.getParticipantData(participantId);
            consentData.push(participantData);
          } catch (error) {
            console.warn(`Failed to load participant data from Blob for ${participantId}:`, error);
          }
        }

        if (consentData.length > 0) {
          console.log(`Loaded ${consentData.length} participants from Vercel Blob`);

          // Kuzuにも保存
          for (const data of consentData) {
            if (kuzuManager) {
              const participant: Participant = {
                id: data.participantId,
                signature: data.signature,
                agreedAt: data.agreedAt || new Date().toISOString(),
                agreements: data.agreements || {}
              };

              try {
                await kuzuManager.saveParticipant(participant);
              } catch (saveError) {
                console.warn('Failed to save participant to Kuzu:', saveError);
              }
            }
          }

          return consentData;
        }
      } catch (blobError) {
        console.warn('Failed to load from Vercel Blob, falling back to file system:', blobError);
      }
    }

    // Fallback: ファイルシステムから読み込み
    const databasePath = join(ARTIFACTS_CACHE_PATH, 'database.jsonl');
    if (!existsSync(databasePath)) {
      return [];
    }

    const content = readFileSync(databasePath, 'utf-8');
    const lines = content.trim().split('\n');

    const consentData = lines.map(line => {
      try {
        const record = JSON.parse(line);
        if (record.type === 'consent') {
          return record.data as ConsentData;
        }
      } catch (error) {
        console.error('Error parsing database line:', error);
      }
      return null;
    }).filter((data): data is ConsentData => data !== null);

    // 読み込んだデータをBlobに保存
    if (blobStorage) {
      for (const data of consentData) {
        try {
          await blobStorage.saveParticipantData(data.participantId, data);
        } catch (saveError) {
          console.warn('Failed to save participant data to Blob:', saveError);
        }
      }
    }

    // Kuzuにも保存
    if (kuzuManager) {
      for (const data of consentData) {
        const participant: Participant = {
          id: data.participantId,
          signature: data.signature,
          agreedAt: data.agreedAt || new Date().toISOString(),
          agreements: data.agreements || {}
        };

        try {
          await kuzuManager.saveParticipant(participant);
        } catch (saveError) {
          console.warn('Failed to save participant to Kuzu:', saveError);
        }
      }
    }

    return consentData;
  } catch (error) {
    console.error('Error loading consent data from database:', error);
    return [];
  }
}

// Get all participant directories
export function getParticipantDirectories(): string[] {
  try {
    const entries = readdirSync(ARTIFACTS_CACHE_PATH, { withFileTypes: true });
    return entries
      .filter(entry => entry.isDirectory())
      .map(entry => entry.name);
  } catch (error) {
    console.error('Error reading participant directories:', error);
    return [];
  }
}

// Load participant data from individual directory
export function loadParticipantData(participantId: string): Participant | null {
  try {
    const participantPath = join(ARTIFACTS_CACHE_PATH, participantId);
    if (!existsSync(participantPath)) {
      return null;
    }

    // Load consent data
    const consentPath = join(participantPath, 'consent.json');
    if (!existsSync(consentPath)) {
      return null;
    }

    const consentData: ConsentData = JSON.parse(readFileSync(consentPath, 'utf-8'));

    // Check for session data
    const sessionDataPath = join(participantPath, 'session_data.json');
    const hasSessionData = existsSync(sessionDataPath);

    // Check for video files
    const entries = readdirSync(participantPath);
    const videoFiles = entries.filter(entry => entry.endsWith('.webm'));

    return {
      id: participantId,
      signature: consentData.signature,
      agreedAt: new Date(consentData.agreedAt),
      hasSessionData,
      hasVideoFiles: videoFiles.length > 0,
      videoFiles
    };
  } catch (error) {
    console.error(`Error loading participant data for ${participantId}:`, error);
    return null;
  }
}

// Load session data for a participant
export async function loadSessionData(participantId: string): Promise<SessionData | null> {
  try {
    // Kuzuデータベースからセッションデータを取得（一本化）
    if (kuzuManager) {
      try {
        // Kuzuからセッションデータを取得
        // 実際のクエリ実装はKuzuManagerで実装する必要がある
        // 現時点では仮の実装
        console.log(`Loading session data from Kuzu for ${participantId}`);
        // TODO: KuzuManagerにgetSessionDataメソッドを実装
        return null; // 仮実装
      } catch (kuzuError) {
        console.warn('Failed to load session data from Kuzu:', kuzuError);
        return null;
      }
    } else {
      console.warn('Kuzu manager not available');
      return null;
    }
  } catch (error) {
    console.error(`Error loading session data for ${participantId}:`, error);
    return null;
  }
}

// Parse word responses from session events
export function parseWordResponsesFromEvents(events: SessionEvent[]): Array<{
  stimulusWord: string;
  responseWord: string;
  reactionTimeMs: number;
  isDelayed: boolean;
  timestamp: number;
}> {
  const wordResponses: Array<{
    stimulusWord: string;
    responseWord: string;
    reactionTimeMs: number;
    isDelayed: boolean;
    timestamp: number;
  }> = [];

  // Group events by word
  const wordEvents: Record<string, SessionEvent[]> = {};

  events.forEach(event => {
    if (event.payload?.word) {
      const word = event.payload.word;
      if (!wordEvents[word]) {
        wordEvents[word] = [];
      }
      wordEvents[word].push(event);
    }
  });

  // Process each word's events
  Object.entries(wordEvents).forEach(([word, wordEventList]) => {
    const wordDisplayedEvent = wordEventList.find(e => e.type === 'word_displayed');
    const speechDetectedEvent = wordEventList.find(e => e.type === 'speech_detected');
    const responseWindowClosedEvent = wordEventList.find(e => e.type === 'response_window_closed');

    if (wordDisplayedEvent && speechDetectedEvent && responseWindowClosedEvent) {
      const reactionTime = speechDetectedEvent.timestamp - wordDisplayedEvent.timestamp;
      const isDelayed = responseWindowClosedEvent.timestamp - speechDetectedEvent.timestamp > 1000; // 1秒以上遅延

      wordResponses.push({
        stimulusWord: word,
        responseWord: word, // Assuming the response is the same word for now
        reactionTimeMs: reactionTime,
        isDelayed,
        timestamp: speechDetectedEvent.timestamp
      });
    }
  });

  return wordResponses;
}

// Load all participants data
export async function loadAllParticipants(): Promise<Participant[]> {
  try {
    // Kuzuデータベースから参加者データを取得（一本化）
    if (kuzuManager) {
      try {
        const kuzuParticipants = await kuzuManager.getAllParticipants();
        console.log(`Loaded ${kuzuParticipants.length} participants from Kuzu`);
        return kuzuParticipants.map(kp => ({
          id: kp.id,
          signature: kp.signature,
          agreedAt: kp.agreedAt,
          agreements: kp.agreements,
          hasSessionData: false, // 後で更新
          hasVideoFiles: false, // 後で更新
          videoFiles: []
        }));
      } catch (kuzuError) {
        console.warn('Failed to load participants from Kuzu:', kuzuError);
        return [];
      }
    } else {
      console.warn('Kuzu manager not available');
      return [];
    }
  } catch (error) {
    console.error('Error loading all participants:', error);
    return [];
  }
}

// Load all session data
export async function loadAllSessionData(): Promise<Array<{ participantId: string; sessionData: SessionData }>> {
  try {
    const participants = await loadAllParticipants();
    const sessionDataPromises = participants
      .filter(p => p.hasSessionData)
      .map(async participant => {
        const sessionData = await loadSessionData(participant.id);
        return sessionData ? { participantId: participant.id, sessionData } : null;
      });

    const results = await Promise.all(sessionDataPromises);
    return results.filter((data): data is { participantId: string; sessionData: SessionData } => data !== null);
  } catch (error) {
    console.error('Error loading all session data:', error);
    // Fallback to synchronous loading
    const participantIds = getParticipantDirectories();
    const participants = participantIds
      .map(id => loadParticipantData(id))
      .filter((participant): participant is Participant => participant !== null);

    return participants
      .filter(p => p.hasSessionData)
      .map(participant => {
        const sessionData = loadSessionData(participant.id);
        return sessionData ? { participantId: participant.id, sessionData } : null;
      })
      .filter((data): data is { participantId: string; sessionData: SessionData } => data !== null);
  }
}

// Get participant statistics
export function getParticipantStatistics(participants: Participant[]) {
  const totalParticipants = participants.length;
  const participantsWithSessionData = participants.filter(p => p.hasSessionData).length;
  const participantsWithVideo = participants.filter(p => p.hasVideoFiles).length;

  const completionRate = totalParticipants > 0 ? (participantsWithSessionData / totalParticipants) * 100 : 0;

  return {
    totalParticipants,
    participantsWithSessionData,
    participantsWithVideo,
    completionRate: Math.round(completionRate * 100) / 100
  };
}
