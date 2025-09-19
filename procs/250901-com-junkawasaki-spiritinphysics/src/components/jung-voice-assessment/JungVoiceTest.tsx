"use client";

import React, { useEffect, useRef, useCallback, MutableRefObject, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { useKawasakiStore, JungVoiceTestProps, Word } from './store';
import AudioVisualizer from './AudioVisualizer';
import { JUNG_TEST_WELCOME_MESSAGE } from './constants';

// --- Memoized, Dumb Sub-components ---

const PreflightScreen = React.memo<{
  videoPreviewRef: MutableRefObject<HTMLVideoElement | null>;
  stream: MediaStream | null;
  deviceStatus: 'idle' | 'pending' | 'success' | 'error';
  error: string | null;
  onStartSession: () => void;
}>(({ videoPreviewRef, stream, deviceStatus, error, onStartSession }) => {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  useEffect(() => {
    if (stream && videoPreviewRef.current) {
        videoPreviewRef.current.srcObject = stream;
        videoPreviewRef.current.play().catch(e => {
            if (e.name !== 'AbortError') console.error("Video play error:", e);
        });
    }
  }, [stream, videoPreviewRef]);

  const playWelcomeAudio = () => {
    if (audioRef.current) {
      audioRef.current.play();
    }
  };

  return (
    <div className="space-y-4">
      <h2 className="text-2xl font-bold">デバイスチェック</h2>

      <Card className="p-4">
        <CardHeader>
            <CardTitle>ようこそ</CardTitle>
        </CardHeader>
        <CardContent className="text-left">
          <p className="whitespace-pre-wrap">{JUNG_TEST_WELCOME_MESSAGE}</p>
          <audio ref={audioRef} src="/audio/jung-voice-assessment/welcome_message.mp3" autoPlay />
          <Button onClick={playWelcomeAudio} className="mt-4">
            説明をもう一度聞く
          </Button>
        </CardContent>
      </Card>

      <div className="relative w-full max-w-md mx-auto aspect-video bg-gray-900 rounded-md overflow-hidden mb-4 flex items-center justify-center">
        <video ref={videoPreviewRef} autoPlay playsInline muted className="w-full h-full object-cover"></video>
        {deviceStatus !== 'success' && (
          <div className="absolute inset-0 flex items-center justify-center text-center p-4">
            <p className="text-white/80 text-lg">
              {deviceStatus === 'pending' && 'カメラとマイクを準備しています...'}
              {deviceStatus === 'error' && 'デバイスにアクセスできませんでした。'}
            </p>
          </div>
        )}
      </div>
      {deviceStatus === 'success' && stream && (
        <div className="space-y-3 text-center">
          <p className="text-green-500">カメラとマイクの準備ができました。</p>
          <AudioVisualizer stream={stream} />
        </div>
      )}
      {error && <p className="text-red-500 mb-4">{error}</p>}
      <Button onClick={onStartSession} size="lg" disabled={!stream}>
        セッションを開始
      </Button>
    </div>
  );
});
PreflightScreen.displayName = 'PreflightScreen';


const SessionScreen = React.memo<{
  videoPreviewRef: MutableRefObject<HTMLVideoElement | null>;
  stream: MediaStream | null;
  currentSession: 1 | 2;
  currentWordIndex: number;
  stimulusWords: Word[];
  onResponse: (response: string, audioBlob: Blob) => void;
}>((
    {
        videoPreviewRef,
        stream,
        currentSession,
        currentWordIndex,
        stimulusWords,
        onResponse,
    }
) => {
    const { logEvent } = useKawasakiStore();
    const [recognizedText, setRecognizedText] = useState('');
    const [isListening, setIsListening] = useState(false);
    const recognitionRef = useRef<SpeechRecognition | null>(null);
    const mediaRecorderRef = useRef<MediaRecorder | null>(null);
    const audioChunksRef = useRef<Blob[]>([]);
    const stimulusAudioRef = useRef<HTMLAudioElement | null>(null);
    const advanceOnSpeechTimerRef = useRef<NodeJS.Timeout | null>(null);

    useEffect(() => {
        if (stream && videoPreviewRef.current) {
            videoPreviewRef.current.srcObject = stream;
        }
    }, [stream, videoPreviewRef]);

    // Speech Synthesis and Recognition Effect
    useEffect(() => {
        if (currentWordIndex < stimulusWords.length) {
            const currentWord = stimulusWords[currentWordIndex];
            const audio = stimulusAudioRef.current;
            
            let recognitionStartTimer: NodeJS.Timeout | null = null;
            let audioContext: AudioContext | null = null;
            let animationFrameId: number;

            const startRecognitionAndDetection = () => {
                const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
                if (SpeechRecognition) {
                    const recognition = new SpeechRecognition();
                    recognition.lang = 'ja-JP';
                    recognition.interimResults = true;
                    recognition.continuous = false;
                    recognitionRef.current = recognition;

                    recognition.onstart = () => setIsListening(true);
                    recognition.onend = () => setIsListening(false);

                    recognition.onresult = (event) => {
                        const transcript = Array.from(event.results).map(result => result[0]).map(result => result.transcript).join('');
                        setRecognizedText(transcript);
                        if (event.results[0].isFinal) {
                            onResponse(transcript, new Blob());
                            if (recognitionRef.current) {
                                recognitionRef.current.stop();
                                // TODO: 認識結果を保存する
                            }
                        }
                    };
                    
                    recognition.start();

                    if (stream) {
                        audioContext = new AudioContext();
                        const source = audioContext.createMediaStreamSource(stream);
                        const analyser = audioContext.createAnalyser();
                        const dataArray = new Uint8Array(analyser.fftSize);
                        source.connect(analyser);
                        let speechHasBeenDetected = false;
                        const checkSpeaking = () => {
                            if (speechHasBeenDetected) return;
                            analyser.getByteTimeDomainData(dataArray);
                            let sum = 0;
                            for (let i = 0; i < dataArray.length; i++) {
                                const val = (dataArray[i] - 128) / 128;
                                sum += val * val;
                            }
                            const volume = Math.sqrt(sum / dataArray.length);
                            if (volume > 0.05) {
                                speechHasBeenDetected = true;
                                console.log("👄 発話あり");
                                logEvent('speech_detected', { word: currentWord.word, key: currentWord.key });
                                if (advanceOnSpeechTimerRef.current) clearTimeout(advanceOnSpeechTimerRef.current);
                                advanceOnSpeechTimerRef.current = setTimeout(() => {
                                    console.log("1秒経過。認識を停止して次の単語へ。");
                                    if (recognitionRef.current) {
                                        recognitionRef.current.stop();

                                    }

                                    speechSynthesis.cancel();
                                    if (audio) audio.pause();
                                    if (recognitionStartTimer) clearTimeout(recognitionStartTimer);
                                    if (recognitionRef.current && isListening) recognitionRef.current.stop();
                                    if (mediaRecorderRef.current && mediaRecorderRef.current.state === "recording") mediaRecorderRef.current.stop();
                                    if (animationFrameId) cancelAnimationFrame(animationFrameId);
                                    if (audioContext && audioContext.state === 'running') audioContext.close();
                                    if (advanceOnSpeechTimerRef.current) clearTimeout(advanceOnSpeechTimerRef.current);
                                }, 1000);
                            }
                            if (!speechHasBeenDetected) {
                                animationFrameId = requestAnimationFrame(checkSpeaking);
                            }
                        };
                        checkSpeaking();
                    }
                }
            };

            if (audio) {
                const audioSrc = `/audio/jung-voice-assessment/${currentWord.key}.mp3`;
                audio.src = audioSrc;
                audio.play()
                    .then(() => {
                        recognitionStartTimer = setTimeout(startRecognitionAndDetection, 1000);
                    })
                    .catch(err => {
                        if (err.name !== 'AbortError') {
                            console.error(`Could not play audio ${audioSrc}, falling back to speech synthesis.`, err);
                            const utterance = new SpeechSynthesisUtterance(currentWord.word);
                            utterance.onstart = () => {
                                recognitionStartTimer = setTimeout(startRecognitionAndDetection, 1000);
                            };
                            speechSynthesis.speak(utterance);
                        }
                    });
            } else {
                const utterance = new SpeechSynthesisUtterance(currentWord.word);
                utterance.onstart = () => {
                    recognitionStartTimer = setTimeout(startRecognitionAndDetection, 1000);
                };
                speechSynthesis.speak(utterance);
            }
        }

        return () => {
            // Cleanup function - capture current values at cleanup time
            // eslint-disable-next-line react-hooks/exhaustive-deps
            const currentMediaRecorder = mediaRecorderRef.current;
            const currentAnimationFrameId = animationFrameId;
            const currentAudioContext = audioContext;
            const currentAdvanceTimer = advanceOnSpeechTimerRef.current;
            const currentRecognition = recognitionRef.current;
            const currentIsListening = isListening;
            const currentAudio = audio;
            const currentRecognitionStartTimer = recognitionStartTimer;

            speechSynthesis.cancel();
            if (currentAudio) currentAudio.pause();
            if (currentRecognitionStartTimer) clearTimeout(currentRecognitionStartTimer);
            if (currentRecognition && currentIsListening) currentRecognition.stop();
            if (currentMediaRecorder && currentMediaRecorder.state === "recording") currentMediaRecorder.stop();
            if (currentAnimationFrameId) cancelAnimationFrame(currentAnimationFrameId);
            if (currentAudioContext && currentAudioContext.state === 'running') currentAudioContext.close();
            if (currentAdvanceTimer) clearTimeout(currentAdvanceTimer);
        };
    }, [currentWordIndex, stimulusWords, onResponse, stream, isListening, logEvent, currentSession]);
  
  if (currentWordIndex >= stimulusWords.length) {
    return <div>次の単語を読み込み中...</div>;
  }

  const progress = ((currentWordIndex + 1) / stimulusWords.length) * 100;

  return (
    <div className="space-y-4 flex flex-col items-center">
      {/* <div className="relative w-40 h-32 mx-auto bg-gray-900 rounded-md overflow-hidden mb-2 flex items-center justify-center">
        <video ref={videoPreviewRef} autoPlay playsInline muted className="w-full h-full object-cover"></video>
      </div> */}
      <audio ref={stimulusAudioRef} />
       <div className="w-full max-w-md">
          <p className="text-sm text-gray-500 mb-1">
              セッション {currentSession} - 単語 {currentWordIndex + 1} / {stimulusWords.length}
          </p>
          <div className="w-full bg-gray-200 rounded-full h-2.5">
              <div className="bg-blue-600 h-2.5 rounded-full" style={{ width: `${progress}%` }}></div>
          </div>
      </div>
      <h2 className="text-6xl font-bold my-8 h-20 flex items-center justify-center">{stimulusWords[currentWordIndex].word}</h2>
      <div className="h-24 w-full max-w-md">
        {stream && <AudioVisualizer stream={stream} />}
      </div>
      <div className="h-8 text-xl text-gray-600">
        {isListening ? '聞き取り中...' : ''}
        {recognizedText && `認識結果: ${recognizedText}`}
      </div>
    </div>
  );
});
SessionScreen.displayName = 'SessionScreen';


const BreakScreen = React.memo<{ onStartNextSession: () => void; }>(({ onStartNextSession }) => (
  <div className="space-y-4">
    <h2 className="text-2xl font-bold">セッション1が完了しました</h2>
    <p>短い休憩を取ってください。準備ができたら、セッション2を開始してください。</p>
    <Button onClick={onStartNextSession} size="lg">セッション2を開始</Button>
  </div>
));
BreakScreen.displayName = 'BreakScreen';

const CompletionScreen = React.memo<{ onReset: () => void; }>(({ onReset }) => (
    <div className="space-y-4">
      <h2 className="text-2xl font-bold">検査完了</h2>
      <p>ご協力ありがとうございました。データは保存されました。</p>
      <Button onClick={onReset}>新しいセッションを開始する</Button>
    </div>
));
CompletionScreen.displayName = 'CompletionScreen';


// --- Main Component ---

export default function JungVoiceTest({
  numberOfWords = 100,
  className = '',
  onComplete,
}: JungVoiceTestProps) {
  const {
    testStatus,
    currentWordIndex,
    stimulusWords,
    startSession,
    resetTest,
    logEvent,
    saveSessionVideo,
    currentSession,
    advanceToNextWord,
    setMediaStatus,
    startPreflight,
    completeSession,
    deviceStatus,
    setDeviceStatus,
    stream,
    error,
    setStream,
    setError,
    recordWordResponse,
  } = useKawasakiStore();

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const videoChunksRef = useRef<Blob[]>([]);
  const videoPreviewRef = useRef<HTMLVideoElement | null>(null);
  const responseTimerRef = useRef<NodeJS.Timeout | null>(null);
  const wordDisplayedTimeRef = useRef<number | null>(null);
  
  // onComplete callback effect
  useEffect(() => {
    if (testStatus === 'completed' && onComplete) {
      onComplete();
    }
  }, [testStatus, onComplete]);

  // Effect for media initialization and cleanup
  useEffect(() => {
    const initializeMedia = async () => {
      setError(null);
      try {
        const mediaStream = await navigator.mediaDevices.getUserMedia({
          audio: true,
          video: { width: 1280, height: 720 }
        });
        setStream(mediaStream);
        setDeviceStatus('success');
        logEvent('preflight_devices_acquired');
      } catch (err) {
        setDeviceStatus('error');
        setError("カメラまたはマイクへのアクセスに失敗しました。ブラウザの権限設定を確認してください。");
        logEvent('preflight_devices_failed', { error: (err as Error).message });
      }
    };

    if (testStatus === 'preflight' && !stream) {
      initializeMedia();
    }
    
    // Cleanup stream when the test is fully completed or reset
    if ((testStatus === 'completed' || testStatus === 'idle') && stream) {
        stream.getTracks().forEach(track => track.stop());
        setStream(null);
    }

  }, [testStatus, stream, setDeviceStatus, logEvent, setStream, setError]);


  const stopRecording = useCallback(() => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state === "recording") {
      mediaRecorderRef.current.stop(); // onstop will handle saving
    }
  }, []);

  const startRecording = useCallback(async (session: 1 | 2) => {
    if (!stream) {
      logEvent('recording_start_failed', { reason: 'No media stream available.' });
      setError("録画を開始できません。メディアストリームが利用できません。");
      return;
    }
  
    videoChunksRef.current = [];
    try {
      const recorder = new MediaRecorder(stream, { mimeType: 'video/webm; codecs=vp9' });
      mediaRecorderRef.current = recorder;
  
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) videoChunksRef.current.push(event.data);
      };
  
      recorder.onstop = () => {
        const videoBlob = new Blob(videoChunksRef.current, { type: 'video/webm' });
        saveSessionVideo(session, videoBlob);
        logEvent('recording_stopped_and_saved', { session });
        mediaRecorderRef.current = null;
      };
  
      recorder.start();
      logEvent('recording_started', { session });
    } catch (err) {
      logEvent('media_recorder_setup_failed', { error: (err as Error).message });
      setError("MediaRecorderの作成に失敗しました。");
    }
  }, [logEvent, saveSessionVideo, stream, setError]);
  
  // Word display and advancement timer
  useEffect(() => {
    if (testStatus.includes('running') && currentWordIndex >= 0 && currentWordIndex < stimulusWords.length) {
      const word = stimulusWords[currentWordIndex];
      logEvent('word_displayed', { word: word.word, key: word.key });
      console.log(`[JungVoiceTest] Word Displayed: ${currentWordIndex + 1}/${stimulusWords.length} - ${word.word}`);
      wordDisplayedTimeRef.current = Date.now();
      
      setMediaStatus('recording_response');
      logEvent('response_window_opened', { word: word.word });

      responseTimerRef.current = setTimeout(() => {
        logEvent('response_window_closed', { word: word.word });
        if (currentWordIndex >= stimulusWords.length - 1) {
          console.log(`[JungVoiceTest] Last word timeout. Calling completeSession for session ${currentSession}`);
          completeSession();
        } else {
          advanceToNextWord();
        }
      }, 6000);

      return () => {
        if (responseTimerRef.current) clearTimeout(responseTimerRef.current);
      }
    }
  }, [currentWordIndex, testStatus, stimulusWords, advanceToNextWord, logEvent, setMediaStatus, completeSession, currentSession]);
  
  // Stop recording when a session or the test completes
  useEffect(() => {
    if(testStatus === 'session-1-complete' || testStatus === 'completed'){
        console.log(`[JungVoiceTest] Status is ${testStatus}, stopping recording.`);
        stopRecording();
    }
  }, [testStatus, stopRecording]);


  const handleConfirmAndStartSession = async () => {
    console.log('[JungVoiceTest] Starting Session 1');
    setError(null);
    await startRecording(1);
    startSession(numberOfWords);
  };
  
  const handleStartSecondSession = async () => {
      console.log('[JungVoiceTest] Starting Session 2');
      await startRecording(2);
      startSession(numberOfWords);
  }

  const handleResponse = useCallback((response: string, audioBlob: Blob) => {
      const reactionTimeMs = wordDisplayedTimeRef.current ? Date.now() - wordDisplayedTimeRef.current : 0;

      recordWordResponse({
          responseWord: response,
          reactionTimeMs: reactionTimeMs,
          audioBlob: audioBlob,
      });

      // The advancement logic is now in the store
      if (responseTimerRef.current) clearTimeout(responseTimerRef.current);
  }, [recordWordResponse]);

  const renderContent = () => {
    console.log(`[JungVoiceTest] Rendering content for testStatus: ${testStatus}`);
    switch (testStatus) {
      case 'preflight':
        return <PreflightScreen 
          videoPreviewRef={videoPreviewRef}
          stream={stream}
          deviceStatus={deviceStatus}
          error={error}
          onStartSession={handleConfirmAndStartSession}
        />;
      case 'session-1-running':
      case 'session-2-running':
        return <SessionScreen 
          videoPreviewRef={videoPreviewRef}
          stream={stream}
          currentSession={currentSession}
          currentWordIndex={currentWordIndex}
          stimulusWords={stimulusWords}
          onResponse={handleResponse}
        />;
      case 'session-1-complete':
        return <BreakScreen onStartNextSession={handleStartSecondSession} />;
      case 'completed':
        return <CompletionScreen onReset={resetTest} />;
      case 'idle':
      default:
        return null;
    }
  };

  return (
    <Card className={`text-center p-6 ${className}`}>
      <CardHeader>
        {/* <CardTitle>ユング式言語連想検査</CardTitle> */}
      </CardHeader>
      <CardContent>
        {error && testStatus !== 'preflight' && <p className="text-red-500 mb-4">{error}</p>}
        {renderContent()}
      </CardContent>
    </Card>
  );
} 