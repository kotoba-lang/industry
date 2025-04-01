"use client";

import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Hume, HumeClient } from 'hume';
import { v4 as uuidv4 } from 'uuid';
import { Button } from '../ui/button';
import { JungVoiceTestProps, WordResponse, TestResults, Message } from './types';
import { JungVoiceAssessmentPropsSchema, WordResponseSchema, TestResultsSchema } from './schema';
import { getAudioFromCache, saveAudioToCache, getAudioCacheSize } from './utils/audioCache';
import AudioCacheManager from './utils/cacheManager';
import { 
  getCombinedCacheStats, 
  getAudioFromCombinedCache, 
  saveAudioToCombinedCache,
  CacheSettings,
  CacheType,
  CacheStats
} from './utils/combinedAudioCache';
import CombinedCacheManager from './utils/combinedCacheManager';

// ヒューム音声生成のインターフェース定義
interface SpeechRecognition extends EventTarget {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  start: () => void;
  stop: () => void;
  abort: () => void;
  onresult: (event: any) => void;
  onerror: (event: any) => void;
  onend: () => void;
}

// ユングの100の刺激語（1910年の論文より）
const JUNG_STIMULUS_WORDS = [
  'head', 'green', 'water', 'to sing', 'dead', 'long', 'ship', 'to pay', 'window', 'friendly',
  'to cook', 'to ask', 'cold', 'stem', 'to dance', 'village', 'lake', 'sick', 'pride', 'to cook',
  'ink', 'angry', 'needle', 'to swim', 'voyage', 'blue', 'lamp', 'to sin', 'bread', 'rich',
  'tree', 'to prick', 'pity', 'yellow', 'mountain', 'to die', 'salt', 'new', 'custom', 'to pray',
  'money', 'foolish', 'pamphlet', 'despise', 'finger', 'expensive', 'bird', 'to fall', 'book', 'unjust',
  'frog', 'to part', 'hunger', 'white', 'child', 'to take care', 'pencil', 'sad', 'plum', 'to marry',
  'house', 'dear', 'glass', 'to quarrel', 'fur', 'great', 'turnip', 'to hold', 'triangle', 'to fear',
  'anxious', 'to kiss', 'burn', 'clean', 'door', 'to choose', 'hay', 'contented', 'ridicule', 'to sleep',
  'month', 'nice', 'woman', 'to abuse', 'yellow', 'to come', 'stove', 'sad', 'stem', 'to dance',
  'sea', 'lovely', 'year', 'black', 'bread', 'family', 'to wash', 'cow', 'friend', 'happiness'
];

// ユングは2秒以上の反応時間を「遅延」とみなし、潜在的に重要だと考えた
const DELAYED_REACTION_THRESHOLD_MS = 2000;

// AIガイドメッセージ
const AI_GUIDE_MESSAGES = {
  introduction: "Welcome to Spirit in Physics (Jung's Word Association Test Embedding Model). I'll present a series of words to you. For each word, please respond verbally with the first word that comes to mind. I'll analyze your reaction times and response patterns. When you're ready, say 'begin' or click the start button.",
  nextWord: "Next word:",
  testComplete: "The test is now complete. Thank you for your responses. I'm analyzing your results.",
  delayed: "Next word:",
  normal: "Next word:"
};

export default function JungVoiceTest({
  numberOfWords = 100,
  apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY || '',
  generationId = '795c949a-1510-4a80-9646-7d0863b023ab',
  voiceName = 'David Hume',
  speechRecognitionLang = 'en-US',
  onTestComplete,
  className = '',
}: JungVoiceTestProps) {
  // プロップスのバリデーション
  const validatedProps = JungVoiceAssessmentPropsSchema.parse({
    numberOfWords,
    apiKey,
    generationId,
    voiceName,
    speechRecognitionLang,
    onTestComplete,
    className
  });

  // 使用する刺激語の数を制限し、ランダムに選択する
  const stimulusWords = useMemo(() => {
    const shuffled = [...JUNG_STIMULUS_WORDS].sort(() => Math.random() - 0.5);
    return shuffled.slice(0, validatedProps.numberOfWords || 100);
  }, [validatedProps.numberOfWords]);

  // 状態管理
  const [currentWordIndex, setCurrentWordIndex] = useState<number>(-1); // -1はテスト未開始
  const [userResponse, setUserResponse] = useState<string>('');
  const [responses, setResponses] = useState<WordResponse[]>([]);
  const [startTime, setStartTime] = useState<number | null>(null);
  const [testComplete, setTestComplete] = useState<boolean>(false);
  const [averageReactionTime, setAverageReactionTime] = useState<number>(0);
  const [delayedResponses, setDelayedResponses] = useState<number>(0);
  const [messages, setMessages] = useState<Message[]>([]);
  const [audioUrl, setAudioUrl] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [isApiAvailable, setIsApiAvailable] = useState<boolean>(true);
  
  // 音声認識の状態
  const [isListening, setIsListening] = useState<boolean>(false);
  const [isSpeechSupported, setIsSpeechSupported] = useState<boolean>(false);
  const [isResponseCorrect, setIsResponseCorrect] = useState<boolean | null>(null);
  
  // Refs
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const humeClientRef = useRef<HumeClient | null>(null);
  const recognitionRef = useRef<SpeechRecognition | null>(null);
  const audioUrlsRef = useRef<string[]>([]);
  const isMountedRef = useRef<boolean>(true);

  // 新しい状態変数
  const [cacheSettings, setCacheSettings] = useState<CacheSettings>({
    clientEnabled: true,
    serverEnabled: true,
    preferServer: true
  });
  const [cacheStats, setCacheStats] = useState<CacheStats | null>(null);
  const [showCacheManager, setShowCacheManager] = useState<boolean>(false);

  // コンポーネントのマウント状態を追跡
  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  // 音声認識がサポートされているか確認
  useEffect(() => {
    const SpeechRecognition = (window as any).SpeechRecognition || 
                              (window as any).webkitSpeechRecognition || 
                              (window as any).mozSpeechRecognition || 
                              (window as any).msSpeechRecognition;
    
    const isSpeechRecognitionSupported = !!SpeechRecognition;
    setIsSpeechSupported(isSpeechRecognitionSupported);
    
    if (!isSpeechRecognitionSupported) {
      console.warn('Speech recognition is not supported in this browser');
      setError('Speech recognition is not supported in this browser. Please try using Chrome, Edge, or Safari.');
      return;
    }
    
    try {
      recognitionRef.current = new SpeechRecognition();
      if (recognitionRef.current) {
        recognitionRef.current.continuous = false;
        recognitionRef.current.interimResults = true;
        recognitionRef.current.lang = speechRecognitionLang;
        
        recognitionRef.current.onresult = (event) => {
          const transcript = Array.from(event.results)
            .map((result: any) => result[0])
            .map((result: any) => result.transcript)
            .join('');
          
          setUserResponse(transcript);
        };
        
        recognitionRef.current.onerror = (event) => {
          // Extract error details if available
          const errorType = event.error || 'unknown';
          const errorMessage = event.message || 'No additional details';
          
          console.error(`Speech recognition error: ${errorType}`, {
            type: errorType,
            message: errorMessage,
            details: event
          });
          
          // Handle specific error types
          if (errorType === 'no-speech') {
            // No speech detected, could retry
            console.warn('No speech detected. You may need to speak louder or check your microphone.');
          } else if (errorType === 'not-allowed' || errorType === 'permission-denied') {
            // Permission issues
            setError('Microphone access denied. Please grant permission to use speech recognition.');
          } else if (errorType === 'network') {
            // Network issues
            setError('Network error occurred. Please check your connection and try again.');
          }
          
          setIsListening(false);
          
          // Try to recover if appropriate
          if (['no-speech', 'aborted', 'audio-capture'].includes(errorType)) {
            // These errors can potentially be recovered from
            setTimeout(() => {
              if (isMountedRef.current && currentWordIndex >= 0) {
                startListening();
              }
            }, 1000);
          }
        };
        
        recognitionRef.current.onend = () => {
          setIsListening(false);
          // 音声認識が終了したら自動的に応答を記録
          if (userResponse.trim() !== '' && currentWordIndex >= 0 && isMountedRef.current) {
            recordResponse();
          }
        };
      }
    } catch (error) {
      console.error('Failed to initialize speech recognition:', error);
      setError('Failed to initialize speech recognition. Please reload the page or try a different browser.');
      setIsSpeechSupported(false);
    }
    
    return () => {
      if (recognitionRef.current) {
        try {
          // リスナーを全て削除 - 空の関数を割り当てることでクリア
          recognitionRef.current.onresult = () => {};
          recognitionRef.current.onerror = () => {};
          recognitionRef.current.onend = () => {};
          
          // 実行中なら停止
          if (isListening) {
            recognitionRef.current.stop();
          }
          
          recognitionRef.current.abort();
        } catch (error) {
          console.warn('Error during speech recognition cleanup:', error);
        }
      }
    };
  }, [speechRecognitionLang]);

  // Hume クライアントの初期化
  useEffect(() => {
    try {
      if (!apiKey) {
        console.warn('No API key provided. Speech generation will be disabled.');
        setIsApiAvailable(false);
        setError('API key not provided. Speech functionality disabled.');
      } else {
        humeClientRef.current = new HumeClient({ apiKey });
        setIsApiAvailable(true);
      }
      
      // 初期AIメッセージを追加
      addMessage(AI_GUIDE_MESSAGES.introduction, 'assistant');
      
      // 初期メッセージを音声で読み上げ
      generateAndPlaySpeech(AI_GUIDE_MESSAGES.introduction);
      
    } catch (err) {
      console.error('Hume client initialization error:', err);
      setError('Failed to initialize Hume client. Speech functionality disabled.');
      setIsApiAvailable(false);
    }

    // クリーンアップ：音声リソースを解放
    return () => {
      cleanupAudioResources();
    };
  }, [apiKey]);

  // 音声URLをクリーンアップする関数
  const cleanupAudioResources = useCallback(() => {
    // 現在再生中の音声を停止
    if (audioRef.current) {
      audioRef.current.pause();
      audioRef.current.src = '';
    }
    
    // 保存されているすべてのオブジェクトURLを解放
    audioUrlsRef.current.forEach(url => {
      try {
        URL.revokeObjectURL(url);
      } catch (err) {
        console.error('Error revoking object URL:', err);
      }
    });
    
    // リストをクリア
    audioUrlsRef.current = [];
  }, []);

  // コンポーネントのアンマウント時にクリーンアップ
  useEffect(() => {
    return () => {
      cleanupAudioResources();
    };
  }, [cleanupAudioResources]);

  // 音声を生成して再生
  const generateAndPlaySpeech = useCallback(async (text: string, onAudioEnd?: () => void): Promise<void> => {
    if (!isApiAvailable || !apiKey || !isMountedRef.current) {
      console.warn('Speech generation skipped: API disabled, no API key, or component unmounted');
      if (onAudioEnd) onAudioEnd();
      return;
    }
    
    try {
      setIsLoading(true);
      
      // 統合キャッシュから音声を取得
      if (cacheSettings.clientEnabled || cacheSettings.serverEnabled) {
        const cacheResult = await getAudioFromCombinedCache(text, voiceName, cacheSettings);
        
        if (cacheResult.blob) {
          // キャッシュから取得した音声を再生
          console.log(`Cache hit for text: "${text}" from ${cacheResult.source}`);
          const url = URL.createObjectURL(cacheResult.blob);
          audioUrlsRef.current.push(url);
          setAudioUrl(url);
          
          // コンポーネントがアンマウントされていたら処理を中止
          if (!isMountedRef.current) {
            URL.revokeObjectURL(url);
            if (onAudioEnd) onAudioEnd();
            return;
          }
          
          if (audioRef.current) {
            playAudio(url, onAudioEnd);
          } else if (onAudioEnd && isMountedRef.current) {
            onAudioEnd();
          }
          
          // キャッシュ統計を更新
          getCombinedCacheStats().then(stats => {
            setCacheStats(stats);
          }).catch(err => {
            console.error('Failed to update cache stats after cache hit:', err);
          });
          
          return;
        }
      }
      
      // キャッシュになければAPIから取得
      // 直接Hume AI TTSエンドポイントを呼び出す
      const apiUrl = 'https://api.hume.ai/v0/tts';
      const headers = {
        'X-Hume-Api-Key': apiKey,
        'Content-Type': 'application/json'
      };
      
      const requestData = {
        utterances: [
          {
            text: text,
            description: voiceName
          }
        ],
        format: {
          type: "mp3"
        },
        num_generations: 1
      };
      
      const fetchResponse = await fetch(apiUrl, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(requestData)
      });
      
      // コンポーネントがアンマウントされていたら処理を中止
      if (!isMountedRef.current) {
        if (onAudioEnd) onAudioEnd();
        return;
      }
      
      if (!fetchResponse.ok) {
        const errorText = await fetchResponse.text();
        throw new Error(`HTTP error! status: ${fetchResponse.status}, message: ${errorText}`);
      }
      
      const response = await fetchResponse.json();
      
      // コンポーネントがアンマウントされていたら処理を中止
      if (!isMountedRef.current) {
        if (onAudioEnd) onAudioEnd();
        return;
      }
      
      // APIはbase64形式の音声データを含む生成の配列を返す
      if (response && response.generations && response.generations.length > 0) {
        const generation = response.generations[0];
        
        // 応答にaudioプロパティ（base64エンコード）があるか確認
        if (generation.audio) {
          // base64をblobに変換
          const binaryString = atob(generation.audio);
          const len = binaryString.length;
          const bytes = new Uint8Array(len);
          
          for (let i = 0; i < len; i++) {
            bytes[i] = binaryString.charCodeAt(i);
          }
          
          const blob = new Blob([bytes], { type: 'audio/mp3' });
          
          // 統合キャッシュに保存（非同期で、続行を待たない）
          if (cacheSettings.clientEnabled || cacheSettings.serverEnabled) {
            saveAudioToCombinedCache(text, voiceName, blob, cacheSettings)
              .then(result => {
                if (result.success) {
                  console.log(`Cached audio for: "${text}" to ${result.savedTo}`);
                  // キャッシュ統計を更新
                  return getCombinedCacheStats();
                }
              })
              .then(stats => {
                if (stats) {
                  setCacheStats(stats);
                }
              })
              .catch(err => {
                console.error('Failed to cache audio:', err);
              });
          }
          
          const url = URL.createObjectURL(blob);
          
          // URL をリストに追加（後でクリーンアップするため）
          audioUrlsRef.current.push(url);
          setAudioUrl(url);
          
          // コンポーネントがアンマウントされていたら処理を中止
          if (!isMountedRef.current) {
            URL.revokeObjectURL(url);
            if (onAudioEnd) onAudioEnd();
            return;
          }
          
          // 音声を再生
          if (audioRef.current) {
            playAudio(url, onAudioEnd);
          } else if (onAudioEnd && isMountedRef.current) {
            // audioRefがない場合は即時コールバック
            onAudioEnd();
          }
        } else if (onAudioEnd && isMountedRef.current) {
          onAudioEnd();
        }
      } else if (onAudioEnd && isMountedRef.current) {
        onAudioEnd();
      }
    } catch (err: any) {
      const errorMessage = err?.message || 'Unknown error';
      console.error('Text-to-speech error:', errorMessage);
      if (isMountedRef.current) {
        setError(`Failed to generate speech: ${errorMessage}`);
      }
      if (onAudioEnd && isMountedRef.current) onAudioEnd();
    } finally {
      if (isMountedRef.current) {
        setIsLoading(false);
      }
    }
  }, [apiKey, isApiAvailable, voiceName, cacheSettings]);

  // 音声再生の共通処理を分離
  const playAudio = useCallback((url: string, onAudioEnd?: () => void) => {
    if (!audioRef.current || !isMountedRef.current) {
      if (onAudioEnd && isMountedRef.current) onAudioEnd();
      return;
    }
    
    const audio = audioRef.current;
    
    // すべてのイベントリスナーをクリア
    const clonedAudio = audio.cloneNode(true) as HTMLAudioElement;
    if (audio.parentNode) {
      audio.parentNode.replaceChild(clonedAudio, audio);
      audioRef.current = clonedAudio;
    }
    
    // 再生終了イベントにコールバックを設定
    if (onAudioEnd) {
      const handleEnded = () => {
        if (isMountedRef.current) {
          onAudioEnd();
        }
        clonedAudio.removeEventListener('ended', handleEnded);
      };
      
      clonedAudio.addEventListener('ended', handleEnded);
    }
    
    // エラーハンドリング
    const handleError = (e: Event) => {
      console.error('Audio playback error:', e);
      if (onAudioEnd && isMountedRef.current) onAudioEnd();
      clonedAudio.removeEventListener('error', handleError);
    };
    
    clonedAudio.addEventListener('error', handleError);
    
    // 音声ファイルを設定して再生
    clonedAudio.src = url;
    
    // 音声再生ボタンを表示して、ユーザーに再生を促す
    if (isMountedRef.current) {
      setAudioUrl(url);
      // 自動再生せずにユーザーインタラクションを待つ
      if (onAudioEnd) {
        // 自動再生に失敗した場合は次のステップに進むことを許可
        setTimeout(() => {
          if (isMountedRef.current) onAudioEnd();
        }, 500);
      }
    }
  }, []);

  // メッセージを追加
  const addMessage = (content: string, sender: 'user' | 'assistant') => {
    const newMessage: Message = {
      id: uuidv4(),
      content,
      sender,
      timestamp: new Date()
    };
    
    setMessages(prev => [...prev, newMessage]);
  };

  // テスト開始
  const startTest = async () => {
    setCurrentWordIndex(0);
    setResponses([]);
    setTestComplete(false);
    
    // 最初の単語を提示
    const firstWordPrompt = `${AI_GUIDE_MESSAGES.nextWord} "${stimulusWords[0]}"`;
    addMessage(firstWordPrompt, 'assistant');
    
    // 音声が終了したら音声認識を開始
    await generateAndPlaySpeech(firstWordPrompt, () => {
      if (isMountedRef.current) {
        setStartTime(Date.now());
        startListening();
      }
    });
  };

  // テストリセット
  const resetTest = () => {
    // 音声リソースをクリーンアップ
    cleanupAudioResources();
    
    setCurrentWordIndex(-1);
    setUserResponse('');
    setResponses([]);
    setTestComplete(false);
    setStartTime(null);
    setAverageReactionTime(0);
    setDelayedResponses(0);
    
    // 初期メッセージを追加
    setMessages([]);
    addMessage(AI_GUIDE_MESSAGES.introduction, 'assistant');
    generateAndPlaySpeech(AI_GUIDE_MESSAGES.introduction);
  };

  // 応答を記録して次の単語へ
  const recordResponse = async () => {
    if (startTime === null || currentWordIndex < 0 || currentWordIndex >= stimulusWords.length || !isMountedRef.current) {
      return;
    }

    const endTime = Date.now();
    const reactionTimeMs = endTime - startTime;
    const isDelayed = reactionTimeMs > DELAYED_REACTION_THRESHOLD_MS;

    const response: WordResponse = {
      stimulus: stimulusWords[currentWordIndex],
      response: userResponse.trim(),
      reactionTimeMs,
      isDelayed,
    };

    // ユーザーの回答をメッセージとして追加
    addMessage(userResponse.trim(), 'user');

    const updatedResponses = [...responses, response];
    setResponses(updatedResponses);
    setUserResponse('');
    setIsResponseCorrect(null);

    // フィードバック後の処理
    if (currentWordIndex < stimulusWords.length - 1) {
      // 次の単語に進む
      const nextIndex = currentWordIndex + 1;
      setCurrentWordIndex(nextIndex);
      
      // 次の単語を読み上げ
      const nextWordPrompt = `${AI_GUIDE_MESSAGES.nextWord} "${stimulusWords[nextIndex]}"`;
      addMessage(nextWordPrompt, 'assistant');
      
      // 次の単語の音声を直接再生
      await generateAndPlaySpeech(nextWordPrompt, () => {
        if (isMountedRef.current) {
          setStartTime(Date.now());
          startListening();
        }
      });
    } else {
      // テスト完了
      completeTest(updatedResponses);
    }
  };

  // レスポンスの正誤評価
  const validateResponse = (isCorrect: boolean) => {
    setIsResponseCorrect(isCorrect);
    
    if (isCorrect) {
      // 正解の場合は次に進む
      recordResponse();
    } else {
      // 不正解の場合は同じ単語をやり直す
      setUserResponse('');
      startListening();
    }
  };

  // テスト完了
  const completeTest = async (finalResponses: WordResponse[]) => {
    if (!isMountedRef.current) return;
    
    const totalReactionTime = finalResponses.reduce((sum, r) => sum + r.reactionTimeMs, 0);
    const avgReactionTime = Math.round(totalReactionTime / finalResponses.length);
    const delayedCount = finalResponses.filter(r => r.isDelayed).length;

    setAverageReactionTime(avgReactionTime);
    setDelayedResponses(delayedCount);
    setTestComplete(true);
    setCurrentWordIndex(-1);

    // 完了メッセージ
    addMessage(AI_GUIDE_MESSAGES.testComplete, 'assistant');
    await generateAndPlaySpeech(AI_GUIDE_MESSAGES.testComplete);

    const results: TestResults = {
      responses: finalResponses,
      averageReactionTimeMs: avgReactionTime,
      delayedResponseCount: delayedCount,
      completedAt: new Date(),
    };

    if (onTestComplete && isMountedRef.current) {
      onTestComplete(results);
    }
  };

  // 音声認識開始
  const startListening = () => {
    if (!recognitionRef.current || !isSpeechSupported || isListening || !isMountedRef.current) {
      return;
    }
    
    // Clear any previous errors
    if (error) setError(null);
    
    try {
      // Some browsers might throw if recognition is already started or in invalid state
      recognitionRef.current.start();
      setIsListening(true);
    } catch (error) {
      console.warn('Speech recognition start error:', error);
      
      // Handle the case where recognition has already started
      if (error instanceof DOMException) {
        // Different browsers may use different error names
        if (error.name === 'InvalidStateError' || error.name === 'NotAllowedError') {
          // Try to reset the recognizer by stopping first
          try {
            recognitionRef.current.stop();
            setIsListening(false);
            
            // Add a small delay before restarting
            setTimeout(() => {
              if (recognitionRef.current && isMountedRef.current) {
                try {
                  recognitionRef.current.start();
                  setIsListening(true);
                } catch (startError) {
                  console.error('Failed to restart speech recognition:', startError);
                  setError('Failed to start speech recognition. Please try again or reload the page.');
                  setIsListening(false);
                }
              }
            }, 300);
          } catch (stopError) {
            console.error('Error stopping speech recognition:', stopError);
            setIsListening(false);
            
            // If completely failed, show error and try to recreate the recognition object
            setError('Speech recognition encountered an error. Please try again.');
            reinitializeSpeechRecognition();
          }
        } else {
          setIsListening(false);
          setError(`Speech recognition error: ${error.message || error.name}`);
        }
      } else {
        setIsListening(false);
        setError('Failed to start speech recognition. Please try again.');
      }
    }
  };
  
  // Add a function to recreate the speech recognition object
  const reinitializeSpeechRecognition = useCallback(() => {
    if (!isMountedRef.current) return;
    
    try {
      // Cleanup existing instance
      if (recognitionRef.current) {
        try {
          recognitionRef.current.onresult = () => {};
          recognitionRef.current.onerror = () => {};
          recognitionRef.current.onend = () => {};
          recognitionRef.current.abort();
        } catch (e) {
          console.warn('Error cleaning up speech recognition:', e);
        }
      }
      
      // Create new instance
      const SpeechRecognition = (window as any).SpeechRecognition || 
                                (window as any).webkitSpeechRecognition || 
                                (window as any).mozSpeechRecognition || 
                                (window as any).msSpeechRecognition;
      
      if (!SpeechRecognition) {
        setIsSpeechSupported(false);
        return;
      }
      
      recognitionRef.current = new SpeechRecognition();
      
      if (recognitionRef.current) {
        recognitionRef.current.continuous = false;
        recognitionRef.current.interimResults = true;
        recognitionRef.current.lang = speechRecognitionLang;
        
        recognitionRef.current.onresult = (event) => {
          const transcript = Array.from(event.results)
            .map((result: any) => result[0])
            .map((result: any) => result.transcript)
            .join('');
          
          setUserResponse(transcript);
        };
        
        // Re-add existing onerror and onend handlers
        recognitionRef.current.onerror = (event) => {
          const errorType = event.error || 'unknown';
          const errorMessage = event.message || 'No additional details';
          
          console.error(`Speech recognition error: ${errorType}`, {
            type: errorType,
            message: errorMessage,
            details: event
          });
          
          // Error handling as before...
          setIsListening(false);
        };
        
        recognitionRef.current.onend = () => {
          setIsListening(false);
          if (userResponse.trim() !== '' && currentWordIndex >= 0 && isMountedRef.current) {
            recordResponse();
          }
        };
        
        setIsSpeechSupported(true);
        console.log('Speech recognition reinitialized');
      } else {
        throw new Error('Failed to create SpeechRecognition instance');
      }
    } catch (error) {
      console.error('Failed to reinitialize speech recognition:', error);
      setIsSpeechSupported(false);
      setError('Failed to initialize speech recognition after error. Please reload the page.');
    }
  }, [speechRecognitionLang, userResponse, currentWordIndex]);

  // 音声認識停止
  const stopListening = () => {
    if (!recognitionRef.current || !isListening) {
      // Already stopped or not initialized
      setIsListening(false);
      return;
    }
    
    try {
      recognitionRef.current.stop();
      // Don't update state here, let the onend handler do it
    } catch (error) {
      console.warn('Error stopping speech recognition:', error);
      setIsListening(false);
      
      // If error is serious, may need to reinitialize
      if (error instanceof DOMException && 
          (error.name === 'InvalidStateError' || error.name === 'NotAllowedError')) {
        console.warn('Recognition in invalid state, attempting to reinitialize');
        setTimeout(() => {
          reinitializeSpeechRecognition();
        }, 500);
      }
    }
  };

  // キャッシュ設定を更新するコールバック
  const handleCacheSettingsChange = useCallback((newSettings: CacheSettings) => {
    setCacheSettings(newSettings);
  }, []);

  // 初期ロード時に統合キャッシュの統計を取得
  useEffect(() => {
    const loadCacheStats = async () => {
      try {
        const stats = await getCombinedCacheStats();
        setCacheStats(stats);
      } catch (err) {
        console.error('Failed to load cache stats:', err);
      }
    };
    
    loadCacheStats();
  }, []);

  return (
    <div className={`max-w-2xl mx-auto p-6 bg-white dark:bg-gray-800 rounded-lg shadow-md ${className}`}>
      <h2 className="text-2xl font-bold mb-6 text-center text-gray-800 dark:text-white">Spirit in Physics (Jung's Word Association Test Embedding Model) - AI Guided</h2>
      
      {/* キャッシュのオン/オフトグル */}
      <div className="flex justify-between items-center mb-2">
        <button
          onClick={() => setShowCacheManager(!showCacheManager)}
          className="text-xs text-blue-500 hover:underline focus:outline-none"
        >
          {cacheStats ? `キャッシュ: ${cacheStats.total.count}件` : 'キャッシュ管理'}
        </button>
      </div>
      
      {/* 統合キャッシュ管理UI */}
      {showCacheManager && (
        <div className="mb-4">
          <CombinedCacheManager 
            initialSettings={cacheSettings}
            onSettingsChange={handleCacheSettingsChange}
          />
        </div>
      )}
      
      {/* 音声再生用の隠し要素 */}
      <audio ref={audioRef} className="hidden" controls />
      
      {/* エラー表示 */}
      {error && (
        <div className="mb-4 p-3 bg-red-100 dark:bg-red-900 text-red-700 dark:text-red-200 rounded-md">
          <div className="flex items-center space-x-2">
            <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
              <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
            </svg>
            <p>{error}</p>
          </div>
          {error.includes('Speech recognition') && (
            <div className="mt-2 text-sm">
              <p>This may be due to:</p>
              <ul className="list-disc pl-5 mt-1">
                <li>Microphone not available or permission denied</li>
                <li>Browser compatibility issues (try Chrome, Edge, or Safari)</li>
                <li>Network connectivity problems</li>
              </ul>
              <div className="mt-2">
                <Button 
                  onClick={reinitializeSpeechRecognition}
                  variant="outline"
                  className="text-xs py-1"
                >
                  Try Again
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
      
      {/* テスト前の説明 */}
      {currentWordIndex === -1 && !testComplete && (
        <div className="text-center">
          {isLoading ? (
            <p className="mb-4">Loading audio...</p>
          ) : (
            <>
              <div className="mb-6 p-4 bg-blue-50 rounded-md">
                <p className="text-lg">{messages.length > 0 ? messages[messages.length - 1].content : ''}</p>
              </div>
              
              {/* 音声再生ボタンを追加 */}
              {audioUrl && (
                <Button
                  onClick={() => audioRef.current?.play()}
                  className="mb-4 flex items-center gap-2"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM9.555 7.168A1 1 0 008 8v4a1 1 0 001.555.832l3-2a1 1 0 000-1.664l-3-2z" clipRule="evenodd" />
                  </svg>
                  Play Instructions
                </Button>
              )}
              
              <Button 
                onClick={startTest} 
                className="px-6 py-2"
                disabled={isLoading}
              >
                Start Test
              </Button>
            </>
          )}
        </div>
      )}

      {/* テスト進行中 */}
      {currentWordIndex >= 0 && currentWordIndex < stimulusWords.length && (
        <div className="text-center">
          <div className="mb-8">
            <p className="text-sm text-gray-600 dark:text-gray-300 mb-1">Word {currentWordIndex + 1} / {stimulusWords.length}</p>
            <h3 className="text-3xl font-bold text-gray-800 dark:text-white">{stimulusWords[currentWordIndex]}</h3>
          </div>
          
          {/* 音声再生ボタン */}
          {audioUrl && (
            <Button 
              onClick={() => audioRef.current?.play()}
              className="mb-4 flex items-center gap-2"
            >
              <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM9.555 7.168A1 1 0 008 8v4a1 1 0 001.555.832l3-2a1 1 0 000-1.664l-3-2z" clipRule="evenodd" />
              </svg>
              Play Word
            </Button>
          )}
          
          <div className="mb-6">
            {isListening ? (
              <div className="flex flex-col items-center">
                <div className="w-16 h-16 bg-red-500 rounded-full flex items-center justify-center mb-2 animate-pulse">
                  <svg xmlns="http://www.w3.org/2000/svg" className="h-8 w-8 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
                  </svg>
                </div>
                <p className="text-sm text-gray-600 dark:text-gray-300">Listening...</p>
                <p className="mt-2 text-lg text-gray-800 dark:text-white">{userResponse}</p>
                <Button
                  onClick={stopListening}
                  className="mt-4 bg-red-600 hover:bg-red-700"
                >
                  Stop
                </Button>
              </div>
            ) : (
              <div className="flex flex-col items-center">
                <Button
                  onClick={startListening}
                  className="mt-2"
                  disabled={!isSpeechSupported || isLoading}
                >
                  Respond by Voice
                </Button>
                {userResponse && (
                  <div className="mt-4">
                    <p className="mb-2 text-lg text-gray-800 dark:text-white">{userResponse}</p>
                    
                    {isResponseCorrect === null ? (
                      <div className="flex gap-2 justify-center">
                        <Button
                          variant="outline"
                          onClick={() => validateResponse(false)}
                          disabled={!userResponse.trim() || isLoading}
                          className="border-red-500 text-red-500 hover:bg-red-50 dark:border-red-400 dark:text-red-400"
                        >
                          Incorrect
                        </Button>
                        <Button
                          onClick={() => validateResponse(true)}
                          disabled={!userResponse.trim() || isLoading}
                        >
                          Correct
                        </Button>
                      </div>
                    ) : (
                      <Button
                        onClick={recordResponse}
                        disabled={!userResponse.trim() || isLoading}
                      >
                        Next
                      </Button>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
          
          <div className="bg-gray-50 dark:bg-gray-700 p-4 rounded-md">
            <h4 className="font-medium mb-2 text-gray-800 dark:text-white">Conversation Log</h4>
            <div className="max-h-48 overflow-y-auto">
              {messages.map((msg) => (
                <div 
                  key={msg.id}
                  className={`mb-2 p-2 rounded-md ${
                    msg.sender === 'assistant' ? 'bg-blue-100 text-left' : 'bg-green-100 text-right'
                  }`}
                >
                  <p className="text-gray-800 dark:text-white">{msg.content}</p>
                  <small className="text-xs text-gray-600 dark:text-gray-300">
                    {msg.timestamp.toLocaleTimeString()}
                  </small>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* テスト完了 */}
      {testComplete && (
        <div className="text-center">
          <h3 className="text-xl font-semibold mb-4 text-gray-800 dark:text-white">Test Complete</h3>
          
          <div className="bg-gray-100 dark:bg-gray-700 p-4 rounded-md mb-6">
            <p className="mb-2">
              <span className="font-medium text-gray-800 dark:text-white">Average reaction time:</span> {averageReactionTime} ms
            </p>
            <p>
              <span className="font-medium text-gray-800 dark:text-white">Delayed responses:</span> {delayedResponses} / {responses.length}
            </p>
          </div>
          
          <h4 className="text-lg font-medium mb-3 text-gray-800 dark:text-white">Your Responses</h4>
          <div className="max-h-80 overflow-y-auto mb-6">
            <table className="w-full border-collapse">
              <thead className="bg-gray-50 dark:bg-gray-700">
                <tr>
                  <th className="px-4 py-2 text-left text-sm font-medium text-gray-800 dark:text-white">Stimulus</th>
                  <th className="px-4 py-2 text-left text-sm font-medium text-gray-800 dark:text-white">Response</th>
                  <th className="px-4 py-2 text-left text-sm font-medium text-gray-800 dark:text-white">Time (ms)</th>
                </tr>
              </thead>
              <tbody>
                {responses.map((resp, index) => (
                  <tr key={index} className={resp.isDelayed ? "bg-yellow-50 dark:bg-yellow-700" : (index % 2 === 0 ? "bg-white dark:bg-gray-800" : "bg-gray-50 dark:bg-gray-700")}>
                    <td className="px-4 py-2 text-sm text-gray-800 dark:text-white">{resp.stimulus}</td>
                    <td className="px-4 py-2 text-sm text-gray-800 dark:text-white">{resp.response}</td>
                    <td className={`px-4 py-2 text-sm ${resp.isDelayed ? "text-red-600 font-medium" : ""} text-gray-800 dark:text-white`}>
                      {resp.reactionTimeMs}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          
          <p className="mb-6 text-sm text-gray-600 dark:text-gray-300">
            Note: Highlighted rows indicate delayed responses (&gt; 2 seconds), which Jung considered
            potentially significant and might indicate emotional complexes.
          </p>
          
          <Button onClick={resetTest} className="px-6 py-2">
            Take Test Again
          </Button>
        </div>
      )}
    </div>
  );
} 