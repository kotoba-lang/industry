import { readFileSync, readdirSync, existsSync, statSync } from 'fs';
import { join } from 'path';

// サーバーサイドでのみインポート
let kuzuManager: any = null;
let Participant: any, Session: any, VideoFile: any;
let blobStorage: any = null;

if (typeof window === 'undefined') {
  try {
    const kuzuModule = require('./database/kuzu-manager');
    kuzuManager = kuzuModule.kuzuManager;
    Participant = kuzuModule.Participant;
    Session = kuzuModule.Session;
    VideoFile = kuzuModule.VideoFile;
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
    await kuzuManager.initialize();
    console.log('Kuzu database initialized successfully');
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
    // まずBlobからデータを取得しようとする
    if (blobStorage) {
      try {
        const sessionData = await blobStorage.getSessionData(participantId);
        if (sessionData) {
          console.log(`Loaded session data from Vercel Blob for ${participantId}`);

          // Kuzuにも保存
          if (kuzuManager) {
            const session: Session = {
              id: `${participantId}_session`,
              participantId: participantId,
              events: sessionData.events,
              createdAt: sessionData.events[0]?.timestamp || new Date().toISOString()
            };

            try {
              await kuzuManager.saveSession(session);
            } catch (saveError) {
              console.warn('Failed to save session to Kuzu:', saveError);
            }
          }

          return sessionData;
        }
      } catch (blobError) {
        console.warn(`Failed to load session data from Blob for ${participantId}:`, blobError);
      }
    }

    // Fallback: ファイルシステムから読み込み
    const sessionDataPath = join(ARTIFACTS_CACHE_PATH, participantId, 'session_data.json');
    if (!existsSync(sessionDataPath)) {
      return null;
    }

    const sessionData: SessionData = JSON.parse(readFileSync(sessionDataPath, 'utf-8'));

    // 読み込んだデータをBlobに保存
    if (blobStorage) {
      try {
        await blobStorage.saveSessionData(participantId, sessionData);
      } catch (saveError) {
        console.warn('Failed to save session data to Blob:', saveError);
      }
    }

    // Kuzuに保存
    if (kuzuManager && sessionData) {
      const session: Session = {
        id: `${participantId}_session`,
        participantId: participantId,
        events: sessionData.events,
        createdAt: sessionData.events[0]?.timestamp || new Date().toISOString()
      };

      try {
        await kuzuManager.saveSession(session);
      } catch (saveError) {
        console.warn('Failed to save session to Kuzu:', saveError);
      }

      // ビデオファイルも保存
      const videoDir = join(ARTIFACTS_CACHE_PATH, participantId);
      if (existsSync(videoDir)) {
        const videoFiles = readdirSync(videoDir)
          .filter(file => file.endsWith('.webm'));

        for (const videoFile of videoFiles) {
          const videoPath = join(videoDir, videoFile);
          const stats = statSync(videoPath);
          const sessionType = videoFile.includes('session-1') ? 'session-1' : 'session-2';

          // ビデオファイルをBlobにアップロード
          if (blobStorage) {
            try {
              await blobStorage.saveVideoFile(participantId, videoPath, sessionType);
            } catch (uploadError) {
              console.warn('Failed to upload video file to Blob:', uploadError);
            }
          }

          // Kuzuにも保存
          const videoFileData: VideoFile = {
            id: `${participantId}_${videoFile}`,
            participantId: participantId,
            sessionId: session.id,
            fileName: videoFile,
            filePath: videoPath,
            fileSize: stats.size,
            createdAt: stats.mtime.toISOString()
          };

          try {
            await kuzuManager.saveVideoFile(videoFileData);
          } catch (saveError) {
            console.warn('Failed to save video file to Kuzu:', saveError);
          }
        }
      }
    }

    return sessionData;
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
    // まずBlobからデータを取得しようとする
    if (blobStorage) {
      try {
        const participantIds = await blobStorage.getAllParticipants();
        if (participantIds.length > 0) {
          const participants: Participant[] = [];

          for (const participantId of participantIds) {
            try {
              // Check if participant exists before attempting to download
              const exists = await blobStorage.participantExists(participantId);
              if (!exists) {
                console.warn(`Participant ${participantId} does not exist in Blob storage, skipping`);
                continue;
              }
              const participantData = await blobStorage.getParticipantData(participantId);

              // セッションとビデオファイルの情報を追加
              let hasSessionData = false;
              let hasVideoFiles = false;
              const videoFiles: string[] = [];

              try {
                await blobStorage.getSessionData(participantId);
                hasSessionData = true;
              } catch (error) {
                // セッションデータなし
              }

              // ビデオファイルのリストを取得
              try {
                const videoFileUrls = await blobStorage.listFiles(`participants/${participantId}/videos/`);
                hasVideoFiles = videoFileUrls.length > 0;
                videoFiles.push(...videoFileUrls.map(url => url.split('/').pop() || ''));
              } catch (error) {
                // ビデオファイルなし
              }

              participants.push({
                id: participantData.participantId,
                signature: participantData.signature,
                agreedAt: participantData.agreedAt,
                agreements: participantData.agreements,
                hasSessionData,
                hasVideoFiles,
                videoFiles
              });
            } catch (error) {
              console.warn(`Failed to load participant ${participantId} from Blob:`, error);
            }
          }

          if (participants.length > 0) {
            console.log(`Loaded ${participants.length} participants from Vercel Blob`);

            // Kuzuにも保存
            if (kuzuManager) {
              for (const participant of participants) {
                const participantData: any = {
                  id: participant.id,
                  signature: participant.signature,
                  agreedAt: participant.agreedAt,
                  agreements: participant.agreements
                };

                try {
                  await kuzuManager.saveParticipant(participantData);
                } catch (saveError) {
                  console.warn('Failed to save participant to Kuzu:', saveError);
                }
              }
            }

            return participants;
          }
        }
      } catch (blobError) {
        console.warn('Failed to load from Vercel Blob, falling back to other methods:', blobError);
      }
    }

    // Kuzuからデータを取得
    if (kuzuManager) {
      try {
        const kuzuParticipants = await kuzuManager.getAllParticipants();
        if (kuzuParticipants.length > 0) {
          return kuzuParticipants.map(kp => ({
            id: kp.id,
            signature: kp.signature,
            agreedAt: kp.agreedAt,
            agreements: kp.agreements,
            hasSessionData: false, // 後で更新
            hasVideoFiles: false, // 後で更新
            videoFiles: []
          }));
        }
      } catch (kuzuError) {
        console.warn('Failed to load from Kuzu:', kuzuError);
      }
    }

    // Fallback: ファイルシステムから読み込み
    const participantIds = getParticipantDirectories();
    const participants = participantIds
      .map(id => loadParticipantData(id))
      .filter((participant): participant is Participant => participant !== null);

    // 読み込んだデータをBlobに保存
    if (blobStorage) {
      for (const participant of participants) {
        try {
          const consentData = {
            participantId: participant.id,
            signature: participant.signature,
            agreedAt: participant.agreedAt,
            agreements: participant.agreements
          };
          await blobStorage.saveParticipantData(participant.id, consentData);
        } catch (saveError) {
          console.warn('Failed to save participant data to Blob:', saveError);
        }
      }
    }

    // Kuzuにも保存
    if (kuzuManager) {
      for (const participant of participants) {
        const participantData: any = {
          id: participant.id,
          signature: participant.signature,
          agreedAt: participant.agreedAt,
          agreements: participant.agreements
        };

        try {
          await kuzuManager.saveParticipant(participantData);
        } catch (saveError) {
          console.warn('Failed to save participant to Kuzu:', saveError);
        }
      }
    }

    return participants;
  } catch (error) {
    console.error('Error loading all participants:', error);
    // Fallback to file-based loading
    const participantIds = getParticipantDirectories();
    return participantIds
      .map(id => loadParticipantData(id))
      .filter((participant): participant is Participant => participant !== null);
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
