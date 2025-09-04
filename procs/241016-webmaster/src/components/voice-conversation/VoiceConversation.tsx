"use client";

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Hume, HumeClient } from 'hume';
import { VoiceConversationProps, Message } from './types';
import { VoiceConversationPropsSchema } from './schema';
import { v4 as uuidv4 } from 'uuid';

// Interface for Hume Emotion Recognition response
interface HumeEmotionResponse {
  results: {
    models: {
      prosody: {
        predictions: Array<{
          name: string;
          score: number;
        }>;
      };
    };
  }[];
}

// Interface for Hume Speech Recognition response
interface HumeSpeechRecognitionResponse {
  transcription: string;
  confidence: number;
}

// Interface for emotion data
interface EmotionData {
  name: string;
  score: number;
  description?: string;
}

// Emotion descriptions mapping
const emotionDescriptions: Record<string, string> = {
  "Admiration": "Respect or praise toward someone",
  "Adoration": "Deep love and respect",
  "Aesthetic Appreciation": "Appreciation for beauty or art",
  "Amusement": "Finding something funny or entertaining",
  "Anger": "Strong feeling of displeasure or hostility",
  "Anxiety": "Feeling of worry or nervousness",
  "Awe": "Feeling of wonder or amazement",
  "Awkwardness": "Feeling uncomfortable or embarrassed",
  "Boredom": "State of being uninterested or weary",
  "Calmness": "State of being peaceful and tranquil",
  "Concentration": "Deep mental focus or attention",
  "Confusion": "State of being uncertain or puzzled",
  "Contempt": "Feeling that someone is worthless or beneath consideration",
  "Contentment": "State of peaceful satisfaction",
  "Desire": "Strong feeling of wanting something",
  "Disappointment": "Sadness from unfulfilled expectations",
  "Disgust": "Strong aversion or repulsion",
  "Distress": "Extreme anxiety, sorrow, or pain",
  "Doubt": "Feeling of uncertainty or lack of conviction",
  "Ecstasy": "Overwhelming feeling of joy or delight",
  "Embarrassment": "Self-conscious discomfort or shame",
  "Empathic Pain": "Feeling pain in response to another's suffering",
  "Entrancement": "State of being captivated or spellbound",
  "Excitement": "Feeling of enthusiasm and eagerness",
  "Fear": "Feeling of being afraid or threatened",
  "Gratitude": "Feeling of thankfulness or appreciation",
  "Guilt": "Feeling of responsibility for wrongdoing",
  "Horror": "Intense feeling of fear, shock, or disgust",
  "Interest": "Feeling of curiosity or engagement",
  "Joy": "Feeling of great happiness",
  "Love": "Deep affection or attachment",
  "Nostalgia": "Sentimental longing for the past",
  "Pain": "Physical or emotional suffering",
  "Pride": "Feeling of satisfaction from achievement",
  "Realization": "Moment of sudden understanding",
  "Relief": "Feeling of reassurance after anxiety or distress",
  "Romance": "Feeling of excitement about love",
  "Sadness": "Feeling of sorrow or unhappiness",
  "Satisfaction": "Fulfillment of a need or desire",
  "Shame": "Painful feeling from consciousness of wrongdoing",
  "Surprise": "Feeling caused by something unexpected",
  "Sympathy": "Feelings of pity or sorrow for someone else",
  "Tiredness": "State of needing rest or sleep",
  "Triumph": "Joy or satisfaction from victory or success"
};

const VoiceConversation: React.FC<VoiceConversationProps> = (props) => {
  // Validate input props with Zod
  const validatedProps = VoiceConversationPropsSchema.parse(props);
  
  const {
    apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY || '',
    generationId = '795c949a-1510-4a80-9646-7d0863b023ab',
    voiceName = 'David Hume',
    initialMessage = 'Hello! How can I assist you today?',
    placeholder = 'Type your message here...',
    speechRecognitionLang = 'en-US',
    onMessageSent,
    onMessageReceived,
    className = '',
  } = validatedProps;

  const [messages, setMessages] = useState<Message[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [audioUrl, setAudioUrl] = useState<string | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const humeClientRef = useRef<HumeClient | null>(null);
  const [isApiAvailable, setIsApiAvailable] = useState<boolean>(true);
  
  // States for speech recognition and emotion detection
  const [isListening, setIsListening] = useState(false);
  const [emotions, setEmotions] = useState<EmotionData[]>([]);
  const [isProcessingEmotion, setIsProcessingEmotion] = useState(false);
  const [isProcessingSpeech, setIsProcessingSpeech] = useState(false);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const [showAllEmotions, setShowAllEmotions] = useState(false);
  const [detectionTimestamp, setDetectionTimestamp] = useState<Date | null>(null);
  const [transcriptionConfidence, setTranscriptionConfidence] = useState<number | null>(null);

  // Initialize the Hume client
  useEffect(() => {
    try {
      if (!apiKey) {
        console.warn('No API key provided. Speech generation will be disabled.');
        setIsApiAvailable(false);
        setError('API key not provided. Speech functionality disabled.');
      } else {
        // Initialize client based on the documentation
        humeClientRef.current = new HumeClient({ 
          apiKey
          // Note: secretKey is optional and not needed for TTS
        });
        
        console.log('Hume client initialized successfully');
        
        // Log structure of the Hume client to help debug available methods
        const logClientStructure = (obj: any, path = 'humeClient') => {
          const seen = new WeakSet();
          
          const logStructure = (obj: any, path: string, depth = 0) => {
            if (depth > 3) return; // Limit recursion depth
            if (!obj || typeof obj !== 'object' || seen.has(obj)) return;
            seen.add(obj);
            
            Object.keys(obj).forEach(key => {
              const value = obj[key];
              const newPath = `${path}.${key}`;
              
              if (typeof value === 'function') {
                console.log(`${newPath} [Function]`);
              } else if (typeof value === 'object' && value !== null) {
                console.log(`${newPath} [Object]`);
                logStructure(value, newPath, depth + 1);
              } else {
                console.log(`${newPath}: ${value}`);
              }
            });
          };
          
          console.log('--- Hume Client Structure ---');
          logStructure(obj, path);
          console.log('--- End of Structure ---');
        };
        
        logClientStructure(humeClientRef.current);
        setIsApiAvailable(true);
      }
      
      // Add initial assistant message
      if (initialMessage) {
        setMessages([
          {
            id: uuidv4(),
            content: initialMessage,
            sender: 'assistant',
            timestamp: new Date(),
          },
        ]);
      }
    } catch (err) {
      console.error('Hume client initialization error:', err);
      setError('Failed to initialize Hume client. Speech functionality disabled.');
      setIsApiAvailable(false);
    }
  }, [apiKey, initialMessage]);

  // Function to generate TTS audio using correct API format
  const generateSpeech = useCallback(async (text: string): Promise<string | null> => {
    if (!isApiAvailable || !apiKey) {
      console.warn('Speech generation skipped: API disabled or no API key');
      return null;
    }
    
    try {
      setIsLoading(true);
      
      console.log('Attempting to generate speech for text:', text);
      
      // Use direct fetch API call to Hume AI TTS endpoint
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
      
      console.log('Sending TTS request:', JSON.stringify(requestData));
      
      const fetchResponse = await fetch(apiUrl, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(requestData)
      });
      
      if (!fetchResponse.ok) {
        const errorText = await fetchResponse.text();
        throw new Error(`HTTP error! status: ${fetchResponse.status}, message: ${errorText}`);
      }
      
      const response = await fetchResponse.json();
      console.log('TTS API call successful');
      
      // The API returns a generations array with audio data in base64 format
      if (response && response.generations && response.generations.length > 0) {
        const generation = response.generations[0];
        console.log('Generation info:', {
          duration: generation.duration,
          encoding: generation.encoding,
          file_size: generation.file_size
        });
        
        // Check if the response has audio property (base64 encoded)
        if (generation.audio) {
          // Convert base64 to blob
          const binaryString = atob(generation.audio);
          const len = binaryString.length;
          const bytes = new Uint8Array(len);
          
          for (let i = 0; i < len; i++) {
            bytes[i] = binaryString.charCodeAt(i);
          }
          
          const blob = new Blob([bytes], { type: 'audio/mp3' });
          const url = URL.createObjectURL(blob);
          
          console.log('Audio URL created successfully');
          return url;
        } else {
          console.error('No audio data in generation:', generation);
          throw new Error('No audio data in TTS response');
        }
      } else {
        console.error('Invalid TTS response structure:', response);
        throw new Error('Invalid TTS response structure');
      }
    } catch (err: any) {
      const errorMessage = err?.message || 'Unknown error';
      console.error('Text-to-speech detailed error:', errorMessage);
      console.error('Error object:', JSON.stringify(err, null, 2));
      setError(`Failed to generate speech: ${errorMessage}`);
      return null;
    } finally {
      setIsLoading(false);
    }
  }, [apiKey, voiceName, isApiAvailable]);

  // Setup audio recording
  const setupAudioRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorderRef.current = new MediaRecorder(stream);
      
      mediaRecorderRef.current.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };
      
      mediaRecorderRef.current.onstop = () => {
        // Process audio when recording stops
        processRecordedAudio();
      };
      
      return true;
    } catch (error) {
      console.error('Error accessing microphone:', error);
      return false;
    }
  };

  // Process recorded audio for both speech recognition and emotion detection
  const processRecordedAudio = async () => {
    if (audioChunksRef.current.length === 0) return;
    
    const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/webm' });
    
    // Process speech recognition and emotion detection in parallel
    setIsProcessingSpeech(true);
    setIsProcessingEmotion(true);
    
    try {
      // Start both API calls concurrently
      const [speechResult] = await Promise.all([
        processSpeechRecognition(audioBlob),
        processEmotionRecognition(audioBlob)
      ]);
      
      // Speech recognition result processing
      if (speechResult) {
        setInputValue(speechResult.transcription);
        setTranscriptionConfidence(speechResult.confidence);
      }
    } catch (error) {
      console.error('Error processing audio:', error);
      setError('Failed to process audio recording');
    } finally {
      setIsProcessingSpeech(false);
      // Note: isProcessingEmotion is set to false in the emotion recognition function
      // because it takes longer to complete
      
      // Clear the audio chunks for the next recording
      audioChunksRef.current = [];
    }
  };

  // Process speech recognition using Hume API
  const processSpeechRecognition = async (audioBlob: Blob): Promise<HumeSpeechRecognitionResponse | null> => {
    if (!isApiAvailable || !apiKey) {
      console.warn('Speech recognition skipped: API disabled or no API key');
      return null;
    }
    
    try {
      // Create form data for API request
      const formData = new FormData();
      formData.append('file', audioBlob, 'speech.webm');
      formData.append('language', speechRecognitionLang.split('-')[0]);
      
      // Make API request to Hume for speech recognition
      const response = await fetch('https://api.hume.ai/v0/speech/transcriptions', {
        method: 'POST',
        headers: {
          'X-Hume-Api-Key': apiKey,
        },
        body: formData,
      });
      
      if (!response.ok) {
        throw new Error(`Hume API error: ${response.status}`);
      }
      
      const data = await response.json();
      
      if (data && data.transcription) {
        return {
          transcription: data.transcription,
          confidence: data.confidence || 0.0
        };
      } else {
        console.error('Invalid speech recognition response:', data);
        return null;
      }
    } catch (error) {
      console.error('Error processing speech recognition:', error);
      return null;
    }
  };

  // Process emotion recognition using Hume API
  const processEmotionRecognition = async (audioBlob: Blob): Promise<void> => {
    if (!isApiAvailable || !apiKey) {
      console.warn('Emotion recognition skipped: API disabled or no API key');
      setIsProcessingEmotion(false);
      return;
    }
    
    try {
      // Create form data for API request
      const formData = new FormData();
      formData.append('file', audioBlob, 'speech.webm');
      formData.append('models', 'prosody');
      
      // Make API request to Hume for emotion recognition
      const response = await fetch('https://api.hume.ai/v0/batch/jobs', {
        method: 'POST',
        headers: {
          'X-Hume-Api-Key': apiKey,
        },
        body: formData,
      });
      
      if (!response.ok) {
        throw new Error(`Hume API error: ${response.status}`);
      }
      
      const jobData = await response.json();
      const jobId = jobData.job_id;
      
      // Poll for job completion
      let jobComplete = false;
      let emotionResult: HumeEmotionResponse | null = null;
      
      while (!jobComplete) {
        // Wait 1 second between polling
        await new Promise(resolve => setTimeout(resolve, 1000));
        
        const statusResponse = await fetch(`https://api.hume.ai/v0/batch/jobs/${jobId}`, {
          headers: {
            'X-Hume-Api-Key': apiKey,
          },
        });
        
        if (!statusResponse.ok) {
          throw new Error(`Hume API status error: ${statusResponse.status}`);
        }
        
        const statusData = await statusResponse.json();
        
        if (statusData.state === 'completed') {
          jobComplete = true;
          emotionResult = statusData.result;
        } else if (statusData.state === 'failed') {
          throw new Error('Hume API job failed');
        }
      }
      
      // Extract emotion data
      if (emotionResult && emotionResult.results && emotionResult.results.length > 0) {
        const prosodyPredictions = emotionResult.results[0].models.prosody.predictions;
        
        // Add descriptions to emotions and sort by score
        const emotionsWithDescriptions = prosodyPredictions
          .map(emotion => ({
            ...emotion,
            description: emotionDescriptions[emotion.name] || `Description for ${emotion.name}`
          }))
          .sort((a, b) => b.score - a.score);
        
        setEmotions(emotionsWithDescriptions);
        setDetectionTimestamp(new Date());
      }
    } catch (error) {
      console.error('Error processing emotion:', error);
    } finally {
      setIsProcessingEmotion(false);
    }
  };

  // Handle sending a new message
  const handleSendMessage = async () => {
    if (!inputValue.trim() || isLoading) return;
    
    // Add user message
    const userMessage: Message = {
      id: uuidv4(),
      content: inputValue,
      sender: 'user',
      timestamp: new Date(),
    };
    
    setMessages(prev => [...prev, userMessage]);
    setInputValue('');
    
    if (onMessageSent) {
      onMessageSent(inputValue);
    }
    
    // Clear previous errors
    setError(null);
    
    // Simulate assistant response (in a real app, this would call an AI API)
    setIsLoading(true);
    
    try {
      // Mock response - replace with actual AI response logic
      const responseText = `I received your message: "${inputValue}"`;
      
      // Generate speech for the response only if API is available
      let speechUrl = null;
      if (isApiAvailable) {
        speechUrl = await generateSpeech(responseText);
      }
      
      // Add assistant message
      const assistantMessage: Message = {
        id: uuidv4(),
        content: responseText,
        sender: 'assistant',
        timestamp: new Date(),
      };
      
      setMessages(prev => [...prev, assistantMessage]);
      
      if (speechUrl) {
        setAudioUrl(speechUrl);
        if (audioRef.current) {
          audioRef.current.src = speechUrl;
          audioRef.current.play().catch(playError => {
            console.error('Error playing audio:', playError);
          });
        }
      }
      
      if (onMessageReceived) {
        onMessageReceived(responseText);
      }
    } catch (err: any) {
      const errorMessage = err?.message || 'Unknown error';
      console.error('Error handling message:', errorMessage);
      setError(`Failed to process message: ${errorMessage}`);
    } finally {
      setIsLoading(false);
    }
  };

  // Handle input change
  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setInputValue(e.target.value);
  };

  // Handle form submission
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    handleSendMessage();
  };

  // Toggle speech recognition
  const toggleListening = async () => {
    if (isListening) {
      // Stop recording
      if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
        mediaRecorderRef.current.stop();
      }
      setIsListening(false);
    } else {
      // Setup audio recording if not already done
      if (!mediaRecorderRef.current) {
        const success = await setupAudioRecording();
        if (!success) {
          console.error('Failed to setup audio recording');
          return;
        }
      }
      
      // Clear previous recording and input
      audioChunksRef.current = [];
      setInputValue('');
      
      // Start recording
      if (mediaRecorderRef.current) {
        mediaRecorderRef.current.start();
        setIsListening(true);
      }
    }
  };

  // Clean up audio URL objects when component unmounts
  useEffect(() => {
    return () => {
      if (audioUrl) {
        URL.revokeObjectURL(audioUrl);
      }
      
      if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
        mediaRecorderRef.current.stop();
      }
    };
  }, [audioUrl]);

  // Calculate emotion visualization data for radar chart
  const getTopEmotionsForRadarChart = () => {
    if (emotions.length === 0) return [];
    return emotions.slice(0, 5).map(emotion => ({
      name: emotion.name,
      value: emotion.score * 100
    }));
  };

  return (
    <div className={`flex flex-col w-full max-w-md mx-auto bg-white rounded-lg shadow-md overflow-hidden ${className}`}>
      <div className="bg-blue-600 text-white p-4">
        <h2 className="text-xl font-semibold">Voice Conversation</h2>
        {!isApiAvailable && (
          <div className="mt-1 text-xs bg-blue-700 rounded px-2 py-1">
            Text only mode - Speech disabled
          </div>
        )}
      </div>

      {/* Messages container */}
      <div className="flex-1 p-4 overflow-y-auto h-80">
        {messages.map((message) => (
          <div 
            key={message.id}
            className={`mb-3 p-3 rounded-lg ${
              message.sender === 'user' 
                ? 'bg-blue-100 ml-auto max-w-[80%]' 
                : 'bg-gray-100 mr-auto max-w-[80%]'
            }`}
          >
            <p>{message.content}</p>
            <small className="text-xs text-gray-500">
              {message.timestamp.toLocaleTimeString()}
            </small>
          </div>
        ))}
        
        {isLoading && (
          <div className="flex justify-center items-center">
            <div className="animate-pulse text-gray-400">Thinking...</div>
          </div>
        )}
        
        {error && (
          <div className="bg-red-100 text-red-800 p-2 rounded-md text-sm">
            {error}
          </div>
        )}
      </div>

      {/* Emotion Recognition Display */}
      {emotions.length > 0 && (
        <div className="p-3 border-t border-gray-200">
          <div className="flex justify-between items-center mb-2">
            <h3 className="text-sm font-semibold">Detected Emotions:</h3>
            {detectionTimestamp && (
              <span className="text-xs text-gray-500">
                {detectionTimestamp.toLocaleTimeString()}
              </span>
            )}
            <button 
              onClick={() => setShowAllEmotions(!showAllEmotions)}
              className="text-xs text-blue-500 hover:text-blue-700"
            >
              {showAllEmotions ? 'Show Top 5' : 'Show All'}
            </button>
          </div>
          
          <div className="space-y-3 max-h-60 overflow-y-auto">
            {(showAllEmotions ? emotions : emotions.slice(0, 5)).map((emotion, index) => (
              <div key={index} className="bg-blue-50 rounded-md p-2">
                <div className="flex justify-between items-center mb-1">
                  <span className="font-medium text-sm">{emotion.name}</span>
                  <span className="text-xs font-semibold">{(emotion.score * 100).toFixed(1)}%</span>
                </div>
                
                {/* Bar chart for emotion score */}
                <div className="w-full bg-gray-200 rounded-full h-2.5">
                  <div 
                    className="bg-blue-600 h-2.5 rounded-full" 
                    style={{ width: `${emotion.score * 100}%` }}
                  ></div>
                </div>
                
                {/* Emotion description */}
                <p className="text-xs text-gray-600 mt-1">{emotion.description}</p>
              </div>
            ))}
          </div>
          
          {/* Summary of dominant emotions */}
          {emotions.length > 0 && (
            <div className="mt-3 p-2 bg-gray-50 rounded-md text-xs">
              <p className="font-semibold">Emotion Analysis Summary:</p>
              <p className="mt-1">
                Primary emotion is <span className="font-medium">{emotions[0].name}</span> ({(emotions[0].score * 100).toFixed(1)}%),
                {emotions[1] && <span> followed by <span className="font-medium">{emotions[1].name}</span> ({(emotions[1].score * 100).toFixed(1)}%)</span>}
                {emotions[2] && <span> and <span className="font-medium">{emotions[2].name}</span> ({(emotions[2].score * 100).toFixed(1)}%)</span>}.
              </p>
            </div>
          )}
        </div>
      )}

      {/* Speech recognition confidence display */}
      {transcriptionConfidence !== null && (
        <div className="px-3 py-2 border-t border-gray-200">
          <div className="flex items-center text-xs text-gray-600">
            <span className="mr-2">Speech Recognition Confidence:</span>
            <div className="flex-1 bg-gray-200 rounded-full h-1.5">
              <div 
                className={`h-1.5 rounded-full ${
                  transcriptionConfidence > 0.8 ? 'bg-green-500' : 
                  transcriptionConfidence > 0.5 ? 'bg-yellow-500' : 'bg-red-500'
                }`}
                style={{ width: `${transcriptionConfidence * 100}%` }}
              ></div>
            </div>
            <span className="ml-2 font-medium">{(transcriptionConfidence * 100).toFixed(0)}%</span>
          </div>
        </div>
      )}

      {/* Input form with voice input button */}
      <form onSubmit={handleSubmit} className="border-t p-4">
        <div className="flex">
          <input
            type="text"
            value={inputValue}
            onChange={handleInputChange}
            placeholder={isListening ? '🎤 Listening...' : placeholder}
            disabled={isLoading || isProcessingEmotion || isProcessingSpeech}
            className="flex-1 border rounded-l-md py-2 px-3 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <button
            type="button"
            onClick={toggleListening}
            disabled={isLoading || isProcessingEmotion || isProcessingSpeech || !isApiAvailable}
            className={`px-3 border-t border-b ${
              isListening 
                ? 'bg-red-500 text-white border-red-500' 
                : isProcessingEmotion || isProcessingSpeech
                  ? 'bg-yellow-100 text-yellow-700 border-yellow-300'
                  : !isApiAvailable
                    ? 'bg-gray-300 text-gray-500 border-gray-300'
                    : 'bg-gray-100 text-gray-700 border-gray-300'
            }`}
            title={
              isListening 
                ? 'Stop listening' 
                : isProcessingEmotion || isProcessingSpeech
                  ? 'Processing audio...' 
                  : !isApiAvailable
                    ? 'API not available'
                    : 'Start voice input'
            }
          >
            {isProcessingEmotion || isProcessingSpeech ? '⏳' : '🎤'}
          </button>
          <button
            type="submit"
            disabled={isLoading || !inputValue.trim()}
            className="bg-blue-600 text-white py-2 px-4 rounded-r-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:bg-blue-300"
          >
            Send
          </button>
        </div>
      </form>

      {/* Hidden audio element for playing TTS */}
      <audio ref={audioRef} className="hidden" controls />

      {/* API Status */}
      <div className="border-t text-xs text-gray-500 px-4 py-2">
        Status: {isApiAvailable ? 
          'API Connected - Voice enabled' : 
          'API Not Available - Text only mode'}
        {isProcessingSpeech && ' | Processing Speech...'}
        {isProcessingEmotion && ' | Processing Emotions...'}
      </div>
    </div>
  );
};

export default VoiceConversation;