"use client";

import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import axios from 'axios'; // Use axios for API calls instead of Hume SDK
import { v4 as uuidv4 } from 'uuid';
import { Button } from '../ui/button';
import { JungVoiceTestProps, TestResults, Message } from './types';
import { JungVoiceAssessmentPropsSchema, TestResultsSchema } from './schema';
import WebcamComponent from '../webcam/WebcamComponent';
import { HumeFaceResponse } from '@/lib/actions/hume-service';
import { saveEmotionData, getEmotionData } from '@/lib/actions/emotion-actions';
import { useHumeEmotion } from '@/providers/HumeEmotionProvider';
import { z } from 'zod';
import { WordResponseWithExtras, withExtras } from './WordResponse';
import { HumeFaceEmotion } from '@/lib/actions/hume-service';

// Create a Zod schema for WordResponse
const WordResponseSchema = z.object({
  stimulusWord: z.string(),
  responseWord: z.string(),
  reactionTimeMs: z.number()
});

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

// IPアドレスに基づく言語設定のマッピング
const IP_LANGUAGE_MAPPING: {[key: string]: string} = {
  // アジア地域
  '124.': 'ja-JP', // 日本
  '203.': 'ja-JP', // 日本の別の範囲
  '211.': 'ko-KR', // 韓国
  '58.': 'zh-CN', // 中国
  '59.': 'zh-CN', // 中国
  '60.': 'zh-CN', // 中国
  '61.': 'zh-CN', // 中国
  '219.': 'zh-CN', // 中国
  '220.': 'zh-CN', // 中国
  '221.': 'zh-CN', // 中国
  // 欧州地域
  '91.': 'en-GB', // イギリス
  '81.': 'de-DE', // ドイツ
  '82.': 'fr-FR', // フランス
  '79.': 'es-ES', // スペイン
  '83.': 'it-IT', // イタリア
  // 北米
  '64.': 'en-US', // アメリカ
  '65.': 'en-US', // アメリカ
  '66.': 'en-US', // アメリカ
  '67.': 'en-US', // アメリカ
  '68.': 'en-US', // アメリカ
  '69.': 'en-US', // アメリカ
  '70.': 'en-US', // アメリカ
  '71.': 'en-US', // アメリカ
  '72.': 'en-US', // アメリカ
  '24.': 'en-CA', // カナダ
};

// IPアドレスプレフィックスに基づいて言語を取得
const getLanguageFromIP = (ipAddress: string): string => {
  // デフォルト言語（IP情報がない場合）
  const defaultLang = 'en-US';
  
  if (!ipAddress) return defaultLang;
  
  // IPアドレスのプレフィックスを確認
  for (const prefix in IP_LANGUAGE_MAPPING) {
    if (ipAddress.startsWith(prefix)) {
      return IP_LANGUAGE_MAPPING[prefix];
    }
  }
  
  return defaultLang;
};

// ユングの100の刺激語（1910年の論文より）
export const JUNG_STIMULUS_WORDS = [
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
  // introduction: "Welcome to Spirit in Physics (Jung's Word Association Test Embedding Model). I'll present a series of words to you. For each word, please respond verbally with the first word that comes to mind. I'll analyze your reaction times and response patterns. When you're ready, say 'begin' or click the start button.",
  nextWord: "Next word:",
  testComplete: "The test is now complete. Thank you for your responses. I'm analyzing your results.",
  delayed: "Next word:",
  normal: "Next word:",
  complete: "Test complete. Thank you for your responses."
};

// 定数として初期メッセージを定義
const INTRODUCTION_MESSAGE = "Welcome to Spirit in Physics (Jung's Word Association Test Embedding Model). I'll present a series of words to you. For each word, please respond verbally with the first word that comes to mind. I'll analyze your reaction times and response patterns. When you're ready, say 'begin' or click the start button.";

export default function JungVoiceTest({
  numberOfWords = 100,
  apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY || '',
  generationId = '795c949a-1510-4a80-9646-7d0863b023ab',
  voiceName = 'David Hume',
  speechRecognitionLang,
  onTestComplete,
  className = '',
}: JungVoiceTestProps) {
  // IPアドレスの状態
  const [ipAddress, setIpAddress] = useState<string>('');
  
  // IPアドレスに基づいた言語設定
  const detectedLanguage = useMemo(() => getLanguageFromIP(ipAddress), [ipAddress]);
  
  // speechRecognitionLangが明示的に指定されていない場合、IPアドレスに基づいて設定
  const effectiveSpeechRecognitionLang = speechRecognitionLang || detectedLanguage || 'en-US';
  
  // プロップスのバリデーション (修正された言語設定を使用)
  const validatedProps = JungVoiceAssessmentPropsSchema.parse({
    numberOfWords,
    apiKey,
    generationId,
    voiceName,
    speechRecognitionLang: effectiveSpeechRecognitionLang,
    onTestComplete,
    className
  });

  // IPアドレスを取得
  useEffect(() => {
    const fetchIPAddress = async () => {
      try {
        const response = await axios.get('https://api.ipify.org?format=json');
        if (response.data && response.data.ip) {
          setIpAddress(response.data.ip);
          console.log(`IP address detected: ${response.data.ip}, Setting language to: ${getLanguageFromIP(response.data.ip)}`);
        }
      } catch (error) {
        console.error('Failed to fetch IP address:', error);
      }
    };
    
    fetchIPAddress();
  }, []);

  // 使用する刺激語の数を制限し、ランダムに選択する
  const stimulusWords = useMemo(() => {
    const shuffled = [...JUNG_STIMULUS_WORDS].sort(() => Math.random() - 0.5);
    return shuffled.slice(0, validatedProps.numberOfWords || 100);
  }, [validatedProps.numberOfWords]);

  // 状態管理
  const [currentWordIndex, setCurrentWordIndex] = useState<number>(-1);
  const [userResponse, setUserResponse] = useState<string>('');
  const [userResponses, setUserResponses] = useState<WordResponseWithExtras[]>([]);
  const [messages, setMessages] = useState<Message[]>([]);
  const [isListening, setIsListening] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [startTime, setStartTime] = useState<number>(0);
  const [testComplete, setTestComplete] = useState<boolean>(false);
  const [audioUrl, setAudioUrl] = useState<string | null>(null);
  const [isWebcamActive, setIsWebcamActive] = useState(false);
  const [emotionTrackingError, setEmotionTrackingError] = useState<string | null>(null);
  const [isApiAvailable, setIsApiAvailable] = useState(true);
  const userIdRef = useRef<string>('');
  const assessmentIdRef = useRef<string>(uuidv4());
  
  // 音声認識の状態
  const [isSpeechSupported, setIsSpeechSupported] = useState<boolean>(false);
  
  // Refs
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const axiosInstance = useRef(axios.create({
    baseURL: 'https://api.hume.ai',
    headers: {
      'X-Hume-Api-Key': apiKey,
      'Content-Type': 'application/json'
    }
  }));
  const recognitionRef = useRef<SpeechRecognition | null>(null);
  const audioUrlsRef = useRef<string[]>([]);
  const isMountedRef = useRef<boolean>(true);

  // 各単語ごとの開始時間を保存するための参照を追加
  const wordStartTimesRef = useRef<Record<number, number>>({});

  // 新しい状態変数 - 感情認識用
  const [currentFaceData, setCurrentFaceData] = useState<HumeFaceResponse | null>(null);
  const [userId, setUserId] = useState<string>('');

  // Get emotion tracking context
  const { 
    enableTracking, 
    disableTracking, 
    isTracking,
    currentEmotion, 
    emotionHistory 
  } = useHumeEmotion();

  // Add missing state setters
  const [averageReactionTime, setAverageReactionTime] = useState<number>(0);
  const [delayedResponses, setDelayedResponses] = useState<number>(0);

  // Add a new ref to track if we're in auto-advance mode
  const autoAdvanceRef = useRef<boolean>(true);

  // Add a ref to track if we're currently processing a response to avoid duplicates
  const isProcessingResponseRef = useRef<boolean>(false);

  // Add a timeout ref to force advance if speech recognition gets stuck
  const advanceTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  // 現在の単語インデックスを参照として保持して直接アクセスできるようにする
  const currentWordIndexRef = useRef<number>(-1);
  
  // currentWordIndexが変更されたら参照も更新するeffect
  useEffect(() => {
    currentWordIndexRef.current = currentWordIndex;
    console.log(`Current word index updated to: ${currentWordIndex}`);
  }, [currentWordIndex]);

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
        recognitionRef.current.lang = effectiveSpeechRecognitionLang;
        
        recognitionRef.current.onresult = (event) => {
          const transcript = Array.from(event.results)
            .map((result: any) => result[0])
            .map((result: any) => result.transcript)
            .join('');
          
          console.log(`Speech recognized: "${transcript}"`, { 
            isFinal: event.results[0]?.isFinal, 
            confidence: event.results[0]?.[0]?.confidence 
          });
          
          // Only process responses when the test is actually running
          if (currentWordIndex < 0) {
            console.log('Test not started yet, ignoring speech input');
            return;
          }
          
          setUserResponse(transcript);
          
          // For final results or if transcript is non-empty after some delay, auto-advance if enabled
          if ((event.results[0]?.isFinal || transcript.trim().length > 0) && autoAdvanceRef.current) {
            // Don't just stop - directly record the response if it's not empty
            if (transcript.trim() !== '' && currentWordIndex >= 0) {
              console.log('Valid response detected, preparing to advance to next word');
              
              // We need to stop first to prevent double recording
              try {
                recognitionRef.current?.stop();
              } catch (e) {
                console.warn('Error stopping recognition:', e);
              }
              
              // Process immediately - no extra type check needed since this is directly in the effect
              recordResponseSafely(transcript);
            }
          }
        };
        
        recognitionRef.current.onerror = (event) => {
          // Extract error details if available
          const errorType = event.error || 'unknown';
          const errorMessage = event.message || 'No additional details';
          
          // Handle specific error types differently
          if (errorType === 'no-speech') {
            // No speech detected - handle as a warning instead of an error
            console.warn('No speech detected:', {
              type: errorType,
              message: errorMessage,
              details: event
            });
            // Show a user-friendly message
            setError('No speech detected. Please speak louder or check your microphone.');
          } else {
            // Log all other errors normally
            console.error(`Speech recognition error: ${errorType}`, {
              type: errorType,
              message: errorMessage,
              details: event
            });
            
            // Handle other specific error types
            if (errorType === 'not-allowed' || errorType === 'permission-denied') {
              // Permission issues
              setError('Microphone access denied. Please grant permission to use speech recognition.');
            } else if (errorType === 'network') {
              // Network issues
              setError('Network error occurred. Please check your connection and try again.');
            } else if (errorType === 'language-not-supported') {
              // Language not supported error
              console.warn(`Language ${effectiveSpeechRecognitionLang} not supported, falling back to en-US`);
              setError(`Language "${effectiveSpeechRecognitionLang}" is not supported by your browser. Falling back to English (US).`);
              
              // Try to fall back to English
              if (recognitionRef.current) {
                recognitionRef.current.lang = 'en-US';
                
                // Restart recognition if it was active
                setTimeout(() => {
                  if (recognitionRef.current && isMountedRef.current) {
                    try {
                      recognitionRef.current.start();
                    } catch (e) {
                      console.error('Error restarting recognition with fallback language:', e);
                    }
                  }
                }, 300);
              }
            }
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
          
          // Only auto-record when not already handled by the onresult handler
          // This serves as a backup in case the final event doesn't trigger
          const currentResponse = userResponse.trim();
          if (currentResponse !== '' && 
              currentWordIndex >= 0 && 
              isMountedRef.current && 
              autoAdvanceRef.current &&
              !isProcessingResponseRef.current) {
            recordResponseSafely(currentResponse);
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
  }, [effectiveSpeechRecognitionLang, currentWordIndex]);

  // Hume クライアントの初期化
  useEffect(() => {
    try {
      if (!apiKey) {
        console.warn('No API key provided. Speech generation will be disabled.');
        setError('API key not provided. Speech functionality disabled.');
      } else {
        setError(null);
      }

      // 初期AIメッセージを追加
      addMessage([{ text: INTRODUCTION_MESSAGE, role: 'assistant' }]);
      
      // 初期メッセージを音声で読み上げ
      generateAndPlaySpeech(INTRODUCTION_MESSAGE);
      
    } catch (err) {
      console.error('Hume client initialization error:', err);
      setError('Failed to initialize Hume client. Speech functionality disabled.');
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
    // Check if the text matches one of the AI guide messages
    let audioPath = '';
    
    // Map the guide messages to their audio files
    if (text === INTRODUCTION_MESSAGE) {
      audioPath = '/audio/Welcome_to_Spirit_in_e4385e4e.mp3';
    } else if (text === AI_GUIDE_MESSAGES.nextWord || 
               text === AI_GUIDE_MESSAGES.delayed || 
               text === AI_GUIDE_MESSAGES.normal) {
      audioPath = '/audio/Next_word__b88adeeb.mp3';
    } else if (text === AI_GUIDE_MESSAGES.testComplete) {
      audioPath = '/audio/The_test_is_now_comp_279d0df1.mp3';
    }
    
    // If we have a matching audio file for the guide message
    if (audioPath) {
      try {
        console.log(`Using pre-recorded audio file for guide message: ${audioPath}`);
        
        // Create audio URL
        const url = audioPath;
        setAudioUrl(url);
        
        // Play audio
        if (audioRef.current) {
          playAudio(url, onAudioEnd);
        } else if (onAudioEnd && isMountedRef.current) {
          onAudioEnd();
        }
        
        return;
      } catch (err) {
        console.error(`Error playing pre-recorded audio for guide message:`, err);
        if (onAudioEnd && isMountedRef.current) onAudioEnd();
        return;
      }
    }
    
    // Check if the text is a Jung stimulus word (needs audio file)
    const isJungWord = JUNG_STIMULUS_WORDS.includes(text.toLowerCase());
    
    // If it's a Jung word, use the pre-recorded audio file
    if (isJungWord) {
      try {
        const audioPath = `/audio/jung_${text.toLowerCase()}.mp3`;
        console.log(`Using pre-recorded audio file: ${audioPath}`);
        
        // Create audio URL
        const url = audioPath;
        setAudioUrl(url);
        
        // Play audio
        if (audioRef.current) {
          playAudio(url, onAudioEnd);
        } else if (onAudioEnd && isMountedRef.current) {
          onAudioEnd();
        }
        
        return;
      } catch (err) {
        console.error(`Error playing pre-recorded audio for "${text}":`, err);
        if (onAudioEnd && isMountedRef.current) onAudioEnd();
        return;
      }
    }
    
    // For non-Jung words, use the "next word" audio instead of API generation
    console.log('Using "Next word" audio instead of generating speech for non-Jung word');
    const nextWordAudioPath = '/audio/Next_word__b88adeeb.mp3';
    try {
      setAudioUrl(nextWordAudioPath);
      if (audioRef.current) {
        playAudio(nextWordAudioPath, onAudioEnd);
      } else if (onAudioEnd && isMountedRef.current) {
        onAudioEnd();
      }
    } catch (err) {
      console.error('Error playing fallback next word audio:', err);
      if (onAudioEnd && isMountedRef.current) onAudioEnd();
    }
  }, []);

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
          console.log('Audio playback ended, executing callback');
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
    
    // 積極的に再生を試みる
    try {
      const playPromise = clonedAudio.play();
      
      // 自動再生ポリシーに対応
      if (playPromise !== undefined) {
        playPromise
          .then(() => {
            console.log('Audio playback started successfully');
          })
          .catch(error => {
            console.warn('Auto-play prevented by browser:', error);
            // 自動再生に失敗した場合は次のステップに進むことを許可
            if (onAudioEnd && isMountedRef.current) {
              setTimeout(() => {
                if (isMountedRef.current) {
                  console.log('Auto-play failed, proceeding with callback');
                  onAudioEnd();
                }
              }, 500);
            }
          });
      }
    } catch (e) {
      console.error('Error during audio play attempt:', e);
      if (onAudioEnd && isMountedRef.current) {
        setTimeout(() => {
          if (isMountedRef.current) onAudioEnd();
        }, 500);
      }
    }
    
    // 音声再生ボタンを表示して、ユーザーに再生を促す
    if (isMountedRef.current) {
      setAudioUrl(url);
    }
  }, []);

  // メッセージを追加
  const addMessage = (messages: Message | Message[]) => {
    if (Array.isArray(messages)) {
      setMessages(prev => [...prev, ...messages]);
    } else {
      setMessages(prev => [...prev, messages]);
    }
  };

  // コンポーネントマウント時にユーザーIDを生成
  useEffect(() => {
    // 既存のユーザーIDを取得するか、新しく生成
    const existingUserId = localStorage.getItem('jung_test_user_id');
    const newUserId = existingUserId || uuidv4();
    
    if (!existingUserId) {
      localStorage.setItem('jung_test_user_id', newUserId);
    }
    
    setUserId(newUserId);
  }, []);

  // テスト開始
  const startTest = async () => {
    setIsLoading(true);
    setIsWebcamActive(true);
    
    try {
      // Reset speech recognition if it's already running
      if (isListening && recognitionRef.current) {
        try {
          recognitionRef.current.stop();
        } catch (e) {
          console.warn('Error stopping recognition before test start:', e);
        }
        setIsListening(false);
      }
      
      // Enable emotion tracking
      try {
        await enableTracking();
      } catch (err) {
        console.error('Failed to enable emotion tracking:', err);
        setEmotionTrackingError('感情トラッキングの有効化に失敗しました。');
      }
      
      // 余分なイントロの再生を削除（すでに初期化時に再生されている）
      // await speakNextWord(AI_GUIDE_MESSAGES.introduction);
      
      // メッセージの記録（すでに記録されているためコメントアウト）
      // addMessage([{ text: AI_GUIDE_MESSAGES.introduction, role: 'assistant' }]);
      
      console.log('Starting test, setting current word index to 0');
      
      // 最初の単語を表示する前に、その単語に対するタイマーをリセット
      delete wordStartTimesRef.current[0];
      setStartTime(0);
      
      setCurrentWordIndex(0);
      currentWordIndexRef.current = 0;
      setIsLoading(false);
      
      // 最初の単語の読み上げと音声認識開始
      setTimeout(() => {
        if (isMountedRef.current) {
          // 最初の単語を読み上げる
          const firstWord = stimulusWords[0];
          if (firstWord) {
            console.log(`Playing first word: "${firstWord}"`);
            generateAndPlaySpeech(firstWord, () => {
              if (!isMountedRef.current) return;
              
              console.log(`First word audio complete, preparing to listen`);
              
              // 音声読み上げ完了時にタイマーを設定（正確な反応時間計測のため）
              const now = Date.now();
              wordStartTimesRef.current[0] = now;
              console.log(`[TIMER] First word (${firstWord}) start time set: ${now}`);
              setStartTime(now);
              
              // 音声認識開始
              startListening();
            });
          } else {
            // 単語が取得できない場合でも音声認識は開始
            startListening();
          }
        }
      }, 500);
    } catch (error) {
      console.error('Error starting test:', error);
      setError('Failed to connect to Hume AI service. Please check your internet connection and try again.');
      setIsLoading(false);
    }
  };

  // Function to generate speech
  const speakNextWord = async (text: string) => {
    // Check if the text matches one of the AI guide messages
    const isGuideMessage = Object.values(AI_GUIDE_MESSAGES).includes(text);
    
    // For Jung stimulus words, modify the text parameter to just use the word itself
    // This ensures we can match with the existing audio files
    const wordOnly = JUNG_STIMULUS_WORDS.includes(text.toLowerCase()) ? text.toLowerCase() : text;
    
    if (!isApiAvailable || !apiKey) {
      console.warn('Hume API is not available, using browser TTS instead');
      // Fallback to browser TTS
      const utterance = new SpeechSynthesisUtterance(text);
      window.speechSynthesis.speak(utterance);
      return;
    }
    
    // Use the existing generateAndPlaySpeech function
    await generateAndPlaySpeech(wordOnly);
  };

  // テストリセット
  const resetTest = () => {
    // Disable emotion tracking when test is reset
    disableTracking();
    setIsWebcamActive(false);
    
    // Reset state variables
    setCurrentWordIndex(-1);
    setUserResponse('');
    setUserResponses([]);
    setMessages([]);
    setIsListening(false);
    setStartTime(0);
    setTestComplete(false);
    setIsLoading(false);
    setAudioUrl(null);
    setError(null);
    setEmotionTrackingError(null);
    
    // Generate a new assessment ID for the next test
    assessmentIdRef.current = uuidv4();
  };

  // 音声認識開始
  const startListening = () => {
    if (!recognitionRef.current || !isSpeechSupported || !isMountedRef.current) {
      return;
    }
    
    // Already listening - don't try to start again
    if (isListening) {
      console.log('Speech recognition already active, not starting again');
      return;
    }
    
    // Clear any previous errors
    if (error) setError(null);
    
    // Clear any existing advance timeout
    if (advanceTimeoutRef.current) {
      clearTimeout(advanceTimeoutRef.current);
      advanceTimeoutRef.current = null;
    }
    
    // Set a safety timeout to force advance after 10 seconds if no response
    advanceTimeoutRef.current = setTimeout(() => {
      console.log('Safety timeout triggered - forcing advance');
      if (isMountedRef.current && isListening && currentWordIndex >= 0) {
        const currentResponse = userResponse.trim() || '(timeout)';
        try {
          if (recognitionRef.current) {
            recognitionRef.current.stop();
          }
        } catch (e) {
          console.warn('Error stopping recognition in timeout:', e);
        }
        recordResponseSafely(currentResponse);
      }
    }, 10000); // 10 seconds max per word
    
    // 現在のタイムスタンプを取得
    const now = Date.now();
    
    // 現在の単語インデックスとタイムスタンプを保存
    if (currentWordIndex >= 0) {
      wordStartTimesRef.current[currentWordIndex] = now;
      console.log(`[TIMER] Word ${currentWordIndex} (${stimulusWords[currentWordIndex]}) start time set: ${now}`);
    }
    
    // グローバルのstartTimeも設定（互換性のため）
    setStartTime(now);
    console.log('Setting start time:', now);
    
    try {
      console.log('Starting speech recognition for word:', stimulusWords[currentWordIndex]);
      // Some browsers might throw if recognition is already started or in invalid state
      recognitionRef.current.start();
      setIsListening(true);
    } catch (error) {
      console.warn('Speech recognition start error:', error);
      
      // Handle the case where recognition has already started
      if (error instanceof DOMException) {
        // Different browsers may use different error names
        if (error.name === 'InvalidStateError' || error.name === 'NotAllowedError') {
          console.log('Recognition already started or in invalid state, trying to reset');
          
          // Try to reset the recognizer by stopping first
          try {
            // Set isListening to true immediately to prevent duplicate start attempts
            setIsListening(true);
            
            recognitionRef.current.stop();
            
            // Add a longer delay before restarting to ensure complete cleanup
            setTimeout(() => {
              if (recognitionRef.current && isMountedRef.current) {
                try {
                  // Only start if not already listening (check state again)
                  if (!isListening) {
                    // Reset start time again before actual restart
                    const newNow = Date.now();
                    if (currentWordIndex >= 0) {
                      wordStartTimesRef.current[currentWordIndex] = newNow;
                      console.log(`[TIMER] Word ${currentWordIndex} (${stimulusWords[currentWordIndex]}) restart time set: ${newNow}`);
                    }
                    setStartTime(newNow);
                    console.log('Resetting start time on restart:', newNow);
                    
                    recognitionRef.current.start();
                    setIsListening(true);
                  }
                } catch (startError) {
                  console.error('Failed to restart speech recognition:', startError);
                  setError('Failed to start speech recognition. Please try again or reload the page.');
                  setIsListening(false);
                  
                  // If still failing, completely recreate the recognition object
                  reinitializeSpeechRecognition();
                }
              }
            }, 500);
          } catch (stopError) {
            console.error('Error stopping speech recognition:', stopError);
            setIsListening(false);
            
            // If completely failed, show error and try to recreate the recognition object
            setError('Speech recognition encountered an error. Please try again.');
            
            // Use a timeout before reinitializing to ensure proper cleanup
            setTimeout(() => {
              reinitializeSpeechRecognition();
            }, 500);
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
    
    // Make sure we're not trying to reinitialize when already listening
    // This might create race conditions
    if (isListening) {
      console.warn('Trying to reinitialize while already listening, delaying...');
      setTimeout(() => {
        if (isMountedRef.current && !isListening) {
          reinitializeSpeechRecognition();
        }
      }, 1000);
      return;
    }
    
    console.log('Reinitializing speech recognition');
    
    try {
      // Cleanup existing instance
      if (recognitionRef.current) {
        try {
          // Set handlers to no-op functions first
          recognitionRef.current.onresult = () => {};
          recognitionRef.current.onerror = () => {};
          recognitionRef.current.onend = () => {};
          
          // Then try to abort
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
      
      // Ensure we're not in listening state before creating a new instance
      setIsListening(false);
      
      // Short timeout to ensure UI state is updated
      setTimeout(() => {
        // Create a new instance
        recognitionRef.current = new SpeechRecognition();
        
        if (recognitionRef.current) {
          recognitionRef.current.continuous = false;
          recognitionRef.current.interimResults = true;
          recognitionRef.current.lang = effectiveSpeechRecognitionLang;
          
          recognitionRef.current.onresult = (event) => {
            const transcript = Array.from(event.results)
              .map((result: any) => result[0])
              .map((result: any) => result.transcript)
              .join('');
            
            // Only process responses when the test is actually running
            if (currentWordIndex < 0) {
              console.log('Test not started yet, ignoring speech input');
              return;
            }
            
            console.log(`Speech recognized: "${transcript}"`, { 
              isFinal: event.results[0]?.isFinal, 
              confidence: event.results[0]?.[0]?.confidence 
            });
            
            setUserResponse(transcript);
            
            // For final results or if transcript is non-empty after some delay, auto-advance if enabled
            if ((event.results[0]?.isFinal || transcript.trim().length > 0) && autoAdvanceRef.current) {
              // Don't just stop - directly record the response if it's not empty
              if (transcript.trim() !== '' && currentWordIndex >= 0) {
                console.log('Valid response detected, preparing to advance to next word');
                
                // We need to stop first to prevent double recording
                try {
                  recognitionRef.current?.stop();
                } catch (e) {
                  console.warn('Error stopping recognition:', e);
                }
                
                // Process immediately - no extra type check needed since this is directly in the effect
                recordResponseSafely(transcript);
              }
            }
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
            
            // Only auto-record when not already handled by the onresult handler
            // This serves as a backup in case the final event doesn't trigger
            const currentResponse = userResponse.trim();
            if (currentResponse !== '' && 
                currentWordIndex >= 0 && 
                isMountedRef.current && 
                autoAdvanceRef.current &&
                !isProcessingResponseRef.current) {
              recordResponseSafely(currentResponse);
            }
          };
          
          setIsSpeechSupported(true);
          console.log('Speech recognition reinitialized');
        } else {
          throw new Error('Failed to create SpeechRecognition instance');
        }
      }, 300);
    } catch (error) {
      console.error('Failed to reinitialize speech recognition:', error);
      setIsSpeechSupported(false);
      setError('Failed to initialize speech recognition after error. Please reload the page.');
    }
  }, [effectiveSpeechRecognitionLang, userResponse, currentWordIndex, isListening]);

  // 音声認識停止
  const stopListening = () => {
    if (!recognitionRef.current) {
      // No recognition reference
      setIsListening(false);
      return;
    }
    
    if (!isListening) {
      // Already stopped
      return;
    }
    
    try {
      console.log('Stopping speech recognition');
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

  // Clean up timeouts when component unmounts
  useEffect(() => {
    return () => {
      if (advanceTimeoutRef.current) {
        clearTimeout(advanceTimeoutRef.current);
      }
    };
  }, []);
  
  // 応答記録時に感情データも合わせて保存
  const recordResponseSafely = async (userInput: string) => {
    try {
      // すでに進行中の処理があれば早期リターン
      if (isProcessingResponseRef.current || testComplete) {
        console.log('Already processing or test complete, ignoring response');
        return;
      }
      
      // 応答記録処理を実行
      await recordResponse(userInput);
    } catch (error) {
      console.error('Error in recordResponseSafely:', error);
      // エラーからの回復を試みる
      isProcessingResponseRef.current = false;
    }
  };

  // 応答記録時に感情データも合わせて保存
  const recordResponse = async (userInput: string) => {
    console.log(`Recording response: "${userInput.trim()}" for word index: ${currentWordIndex}`);
    
    // Don't record responses if test is already complete
    if (testComplete) {
      console.log('Test already complete, ignoring response');
      return;
    }
    
    // Clear any existing advance timeout
    if (advanceTimeoutRef.current) {
      clearTimeout(advanceTimeoutRef.current);
      advanceTimeoutRef.current = null;
    }
    
    // Prevent duplicate recordings
    if (isProcessingResponseRef.current) {
      console.log('Already processing a response, ignoring duplicate call');
      return;
    }
    
    // 処理中フラグを設定
    isProcessingResponseRef.current = true;
    
    // Check if currentWordIndex is valid
    if (currentWordIndex < 0 || currentWordIndex >= stimulusWords.length) {
      console.error(`Invalid currentWordIndex: ${currentWordIndex}, stimulusWords length: ${stimulusWords.length}`);
      setError('Invalid word index. Please restart the test.');
      isProcessingResponseRef.current = false;
      return;
    }
    
    // Stop listening if still active
    if (isListening && recognitionRef.current) {
      try {
        recognitionRef.current.stop();
      } catch (e) {
        console.warn('Error stopping recognition in recordResponse:', e);
      }
      setIsListening(false);
    }
    
    const currentWord = stimulusWords[currentWordIndex];
    
    // Verify the current word exists
    if (!currentWord) {
      console.error(`Current word is undefined at index ${currentWordIndex}`);
      setError('Current word is undefined. Please restart the test.');
      isProcessingResponseRef.current = false;
      return;
    }
    
    console.log(`Current stimulus word: "${currentWord}"`);
    const normalizedResponse = userInput.trim().toLowerCase();
    
    if (!normalizedResponse) {
      console.warn('Empty response received, not recording');
      isProcessingResponseRef.current = false;
      // Restart listening if response was empty
      setTimeout(() => {
        if (isMountedRef.current && currentWordIndex >= 0 && !testComplete) {
          startListening();
        }
      }, 500);
      return;
    }
    
    try {
      // 最小反応時間を100msに設定
      const MIN_REACTION_TIME = 100;
      
      // 反応時間の計算
      const currentTime = Date.now();
      
      // 単語ごとのタイムスタンプをまず確認
      const wordStartTime = wordStartTimesRef.current[currentWordIndex];
      console.log(`[TIMER] Word ${currentWordIndex} (${currentWord}) has start time: ${wordStartTime}`);
      
      // グローバルのstartTimeも確認
      console.log(`[TIMER] Global start time: ${startTime}`);
      
      // 開始時間が0または未設定の場合のチェック
      if (!wordStartTime && !startTime) {
        console.warn('Both word-specific and global start times were not set, using fallback minimum reaction time');
      }
      
      // 単語ごとのタイムスタンプを優先し、バックアップとしてグローバルのstartTimeを使用
      let reactionTimeMs = wordStartTime && wordStartTime > 0 
          ? currentTime - wordStartTime 
          : (startTime && startTime > 0 ? currentTime - startTime : MIN_REACTION_TIME);
      
      console.log(`[TIMER] Raw reaction time calculation: ${currentTime} - ${wordStartTime || startTime} = ${reactionTimeMs}ms`);
      
      // 反応時間が異常に短い場合は最小値に設定
      if (reactionTimeMs < MIN_REACTION_TIME) {
        console.log(`[TIMER] Reaction time too short: ${reactionTimeMs}ms, normalizing to ${MIN_REACTION_TIME}ms`);
        reactionTimeMs = MIN_REACTION_TIME;
      } else if (reactionTimeMs > 30000) { // 30秒以上も不自然
        console.log(`[TIMER] Reaction time too long: ${reactionTimeMs}ms, normalizing to ${MIN_REACTION_TIME}ms`);
        reactionTimeMs = MIN_REACTION_TIME;
      }
      
      const isDelayed = reactionTimeMs > DELAYED_REACTION_THRESHOLD_MS;
      
      console.log(`[TIMER] Final response time: ${reactionTimeMs}ms, isDelayed: ${isDelayed}`);
      
      // 新しい応答を作成
      const response: WordResponseWithExtras = {
        stimulusWord: currentWord,
        responseWord: normalizedResponse,
        reactionTimeMs,
        isDelayed
      };
      
      // Double-check response data is valid before validation
      if (!response.stimulusWord || !response.responseWord) {
        throw new Error(`Invalid response data: stimulusWord=${response.stimulusWord}, responseWord=${response.responseWord}`);
      }
      
      // 応答をバリデーション
      try {
        WordResponseSchema.parse(response);
      } catch (error) {
        console.error('Invalid response data:', error);
        setError('Invalid response data. Please try again.');
        isProcessingResponseRef.current = false;
        return;
      }
      
      // 現在の単語インデックスを確保（状態更新の非同期性に対応するため）
      const currentIndex = currentWordIndex;
      console.log(`Recording response for word index ${currentIndex}, is last word: ${currentIndex + 1 >= stimulusWords.length}`);
      
      // タイムスタンプを明示的にクリア（この単語の計測は完了したため）
      delete wordStartTimesRef.current[currentIndex];
      
      // 応答を記録
      setUserResponses(prevResponses => {
        const updatedResponses = [...prevResponses, response];
        return updatedResponses;
      });
      
      // 音声データを保存 - これは並行して実行
      if (currentFaceData && userId && assessmentIdRef.current) {
        // EmotionDataServiceを使用して感情データを保存
        try {
          await saveEmotionData({
            userId,
            assessmentId: assessmentIdRef.current,
            stimulusWord: currentWord,
            responseWord: normalizedResponse,
            reactionTimeMs,
            faceEmotions: currentEmotion?.emotions || {},
            timestamp: Date.now()
          });
        } catch (err) {
          console.error('Failed to save emotion data:', err);
        }
      }
      
      // 入力フィールドをリセット
      setUserResponse('');
      
      // 次の単語に進むか、テストを完了する
      setTimeout(() => {
        if (!isMountedRef.current) return;
        
        // このインデックスでの処理を完了し、次に進む
        const nextIndex = currentIndex + 1;
        console.log(`Word ${currentIndex} processing complete, preparing for next step. Next index: ${nextIndex}`);
        
        // Check if this is the last word
        if (nextIndex >= stimulusWords.length) {
          // Get the latest user responses to ensure we have all data
          console.log('Last word processed, completing test');
          completeTestSafely();
        } else {
          // First update the index, then proceed with audio
          console.log(`Advancing to next word: ${nextIndex}`);
          moveToWordIndex(nextIndex, isDelayed);
        }
      }, 500);
      
    } catch (error) {
      console.error('Error processing response:', error);
      isProcessingResponseRef.current = false;
      
      // Restart listening if there was an error processing the response
      setTimeout(() => {
        if (isMountedRef.current && currentWordIndex >= 0 && !testComplete) {
          startListening();
        }
      }, 500);
    }
  };

  // テストを安全に完了させる関数
  const completeTestSafely = () => {
    // テスト完了フラグを設定
    setTestComplete(true);
    setIsLoading(false);
    
    if (recognitionRef.current) {
      try {
        recognitionRef.current.stop();
      } catch (e) {
        console.warn('Error stopping recognition in completeTestSafely:', e);
      }
    }
    
    // 遅延応答の数をカウント
    let delayedCount = 0;
    let totalReactionTime = 0;
    
    // ユーザー応答を取得
    const finalResponses = userResponses;
    
    // 結果をフォーマット
    finalResponses.forEach(response => {
      if (response.isDelayed) {
        delayedCount++;
      }
      totalReactionTime += response.reactionTimeMs;
    });
    
    // 平均反応時間を計算
    const avgReactionTime = finalResponses.length > 0 
      ? Math.round(totalReactionTime / finalResponses.length) 
      : 0;
    
    // 結果を保存
    setAverageReactionTime(avgReactionTime);
    setDelayedResponses(delayedCount);
    
    // データ収集にスリープを入れて処理を確実に完了させる
    setTimeout(() => {
      if (!isMountedRef.current) return;
      
      // テスト結果を作成
      const testResults: TestResults = {
        totalWords: finalResponses.length,
        averageReactionTimeMs: avgReactionTime,
        delayedResponsesCount: delayedCount,
        responses: finalResponses,
        completedAt: new Date()
      };
      
      // コールバックが定義されていれば、結果を渡す
      if (onTestComplete) {
        onTestComplete(testResults);
      }
      
      // 音声認識・音声再生のリソースをクリーンアップ
      cleanupAudioResources();
      
      // 音声認識を停止
      if (recognitionRef.current) {
        try {
          // リスナーを全て削除
          recognitionRef.current.onresult = () => {};
          recognitionRef.current.onerror = () => {};
          recognitionRef.current.onend = () => {};
          
          recognitionRef.current.abort();
        } catch (e) {
          console.warn('Error cleaning up speech recognition:', e);
        }
      }
      
      console.log('Test completed successfully with results:', testResults);
    }, 500);
  };
  
  // 特定の単語インデックスに移動する安全な関数
  const moveToWordIndex = async (indexToMoveTo: number, isDelayed: boolean = false) => {
    if (!isMountedRef.current || testComplete) return;
    
    try {
      // バウンダリチェック
      if (indexToMoveTo < 0 || indexToMoveTo >= stimulusWords.length) {
        console.error(`Invalid target index: ${indexToMoveTo}`);
        return;
      }
      
      // 状態更新をバッチに含める
      console.log(`Moving to word index: ${indexToMoveTo}`);
      
      // インデックスを直接更新
      currentWordIndexRef.current = indexToMoveTo; // 参照を直接更新
      setCurrentWordIndex(indexToMoveTo); // 状態も更新
      
      const targetWord = stimulusWords[indexToMoveTo];
      if (!targetWord) {
        console.error(`Target word is undefined at index ${indexToMoveTo}`);
        return;
      }
      
      // 適切なメッセージを選択
      const nextMessage = isDelayed ? AI_GUIDE_MESSAGES.delayed : AI_GUIDE_MESSAGES.normal;
      
      // メッセージを記録
      addMessage([{ text: `${nextMessage} ${targetWord}`, role: 'assistant' }]);
      
      console.log(`Playing audio for word: "${targetWord}"`);
      
      // タイマーを明示的にリセット（確実に新しい測定を開始するため）
      // 単語ごとのタイムスタンプを明示的に削除（新しい単語の測定のため）
      delete wordStartTimesRef.current[indexToMoveTo];
      setStartTime(0);
      
      // 音声が終了した後に呼び出されるコールバック
      const audioCallback = () => {
        if (!isMountedRef.current) return;
        
        console.log(`Audio finished for word: "${targetWord}", preparing to listen`);
        isProcessingResponseRef.current = false; // 処理完了を示す
        
        // 音声認識開始
        setTimeout(() => {
          if (isMountedRef.current && !testComplete) {
            // Double check the index is still what we expect
            if (currentWordIndexRef.current !== indexToMoveTo) {
              console.warn(`Index changed during audio playback: ${currentWordIndexRef.current} vs expected ${indexToMoveTo}`);
              // Try to correct
              currentWordIndexRef.current = indexToMoveTo;
              setCurrentWordIndex(indexToMoveTo);
            }
            
            // 音声再生完了時にタイマーをリセット - ユーザーが単語を聞いてから応答するまでの時間を正確に測定
            const now = Date.now();
            
            // 単語ごとのタイムスタンプも設定
            wordStartTimesRef.current[indexToMoveTo] = now;
            console.log(`[TIMER] Word ${indexToMoveTo} (${targetWord}) callback time set: ${now}`);
            
            setStartTime(now);
            console.log('Setting start time in audioCallback:', now);
            
            startListening();
          }
        }, 300);
      };
      
      // 音声を再生
      await generateAndPlaySpeech(targetWord, audioCallback);
      
    } catch (error) {
      console.error('Error moving to word index:', error);
      isProcessingResponseRef.current = false;
      
      // Try to recover
      setTimeout(() => {
        if (isMountedRef.current && !testComplete) {
          // タイマーをリセットしてからリスニングを開始
          const now = Date.now();
          
          // 単語ごとのタイムスタンプも設定
          if (indexToMoveTo >= 0) {
            wordStartTimesRef.current[indexToMoveTo] = now;
            console.log(`[TIMER] Word ${indexToMoveTo} (${stimulusWords[indexToMoveTo]}) error recovery time set: ${now}`);
          }
          
          setStartTime(now);
          console.log('Setting start time in error recovery:', now);
          startListening();
        }
      }, 500);
    }
  };

  return (
    <div 
      className={`min-h-[50vh] flex items-center justify-center bg-white dark:bg-gray-800 p-8 rounded-lg shadow-md ${className}`}
    >
      {/* Add audio element here */}
      <audio ref={audioRef} className="hidden" controls />
      
      <div className="w-full max-w-2xl">
        {error && (
          <div className="bg-red-100 border-l-4 border-red-500 text-red-700 p-4 mb-6 rounded-md">
            <p>{error}</p>
          </div>
        )}
        
        {emotionTrackingError && (
          <div className="bg-red-100 border-l-4 border-red-500 text-red-700 p-4 mb-6 rounded-md">
            <p>感情認識エラー: {emotionTrackingError}</p>
          </div>
        )}
        
        <div className="bg-white dark:bg-gray-800 rounded-lg shadow p-6">
          <div className="mb-6">
            <h2 className="text-2xl font-bold mb-2 text-gray-800 dark:text-white">
              Spirt in Physics (Jung's Word Association Test Embedding Model)</h2>
            <p className="text-gray-600 dark:text-gray-300">
              This test explores your immediate mental associations. I'll present words, and you respond with the first word that comes to mind.
            </p>
          </div>
          
          {/* Webcam component for facial emotion tracking */}
          {isTracking && currentWordIndex >= 0 && !testComplete && (
            <div className="mb-6">
              <h3 className="text-lg font-semibold mb-2 text-gray-800 dark:text-white">
                表情分析
              </h3>
              <WebcamComponent 
                isActive={isTracking}
                showLabels={true}
                width={320}
                height={240}
              />
              <p className="text-sm text-gray-500 text-center mt-2">
                あなたの表情から感情を分析しています
              </p>
            </div>
          )}
          
          {currentWordIndex < 0 ? (
            <div className="flex flex-col items-center">
              <Button 
                onClick={startTest} 
                disabled={isLoading}
                className="mt-4"
              >
                {isLoading ? 'Connecting...' : 'Start Test'}
              </Button>
              
              {/* Add auto-advance toggle */}
              <div className="mt-4 flex items-center">
                <input
                  type="checkbox"
                  id="autoAdvance"
                  checked={autoAdvanceRef.current}
                  onChange={(e) => {
                    autoAdvanceRef.current = e.target.checked;
                  }}
                  className="mr-2"
                />
                <label htmlFor="autoAdvance" className="text-gray-700 dark:text-gray-300">
                  Auto-advance to next word
                </label>
              </div>
              
              {isLoading && (
                <div className="mt-4 flex items-center">
                  <div className="animate-spin rounded-full h-6 w-6 border-b-2 border-gray-800 dark:border-white"></div>
                  <span className="ml-2 text-gray-700 dark:text-gray-300">Connecting to Hume AI...</span>
                </div>
              )}
            </div>
          ) : !testComplete ? (
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
                    {/* <p className="mt-2 text-lg text-gray-800 dark:text-white">{userResponse}</p> */}
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
                    
                    {/* Only show manual controls when auto-advance is disabled */}
                    {!autoAdvanceRef.current && userResponse && (
                      <div className="mt-4">
                        <p className="mb-2 text-lg text-gray-800 dark:text-white">{userResponse}</p>
                        
                        <Button
                          onClick={() => recordResponseSafely(userResponse)}
                          disabled={!userResponse.trim() || isLoading}
                        >
                          Next
                        </Button>
                      </div>
                    )}
                    
                    {/* Add toggle for auto-advance mode during the test */}
                    <div className="mt-4 flex items-center">
                      <input
                        type="checkbox"
                        id="autoAdvanceRunning"
                        checked={autoAdvanceRef.current}
                        onChange={(e) => {
                          autoAdvanceRef.current = e.target.checked;
                        }}
                        className="mr-2"
                      />
                      <label htmlFor="autoAdvanceRunning" className="text-gray-700 dark:text-gray-300">
                        Auto-advance to next word
                      </label>
                    </div>
                  </div>
                )}
              </div>
              
              <div className="bg-gray-50 dark:bg-gray-700 p-4 rounded-md">
                <h4 className="font-medium mb-2 text-gray-800 dark:text-white">Conversation Log</h4>
                <div className="max-h-48 overflow-y-auto">
                  {messages.map((msg, index) => (
                    <div
                      key={index}
                      className={`mb-2 p-2 rounded-md ${
                        msg.role === 'assistant' ? 'bg-blue-100 text-left' : 'bg-green-100 text-right'
                      }`}
                    >
                      <p className="text-gray-800 dark:text-white">{msg.text}</p>
                      <small className="text-xs text-gray-600 dark:text-gray-300">
                        {new Date().toLocaleTimeString()}
                      </small>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <div className="text-center">
              <h3 className="text-xl font-semibold mb-4 text-gray-800 dark:text-white">Test Complete</h3>
              
              <div className="bg-gray-100 dark:bg-gray-700 p-4 rounded-md mb-6">
                <p className="mb-2">
                  <span className="font-medium text-gray-800 dark:text-white">Average reaction time:</span> <span className="text-gray-800 dark:text-blue-300">{averageReactionTime} ms</span>
                </p>
                <p>
                  <span className="font-medium text-gray-800 dark:text-white">Delayed responses:</span> <span className="text-gray-800 dark:text-blue-300">{delayedResponses} / {userResponses.length}</span>
                </p>
              </div>
              
              <h4 className="text-lg font-medium mb-3 text-gray-800 dark:text-white">Your Responses</h4>
              <div className="max-h-80 overflow-y-auto mb-6">
                <table className="w-full border-collapse">
                  <thead className="bg-gray-50 dark:bg-gray-700">
                    <tr>
                      <th className="px-4 py-2 text-left text-sm font-medium text-gray-800 dark:text-white">Stimulus</th>
                      {/* <th className="px-4 py-2 text-left text-sm font-medium text-gray-800 dark:text-white">Response</th> */}
                      <th className="px-4 py-2 text-left text-sm font-medium text-gray-800 dark:text-white">Time (ms)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {userResponses.map((resp, index) => (
                      <tr key={index} className={withExtras(resp).isDelayed ? "bg-yellow-50 dark:bg-yellow-700" : (index % 2 === 0 ? "bg-white dark:bg-gray-800" : "bg-gray-50 dark:bg-gray-700")}>
                        <td className="px-4 py-2 text-sm text-gray-800 dark:text-white">{resp.stimulusWord}</td>
                        {/* <td className="px-4 py-2 text-sm text-gray-800 dark:text-white">{resp.responseWord}</td> */}
                        <td className={`px-4 py-2 text-sm ${withExtras(resp).isDelayed ? "text-red-600 dark:text-red-400 font-medium" : "text-gray-800 dark:text-blue-300"}`}>
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
      </div>
    </div>
  );
} 