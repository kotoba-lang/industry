"use client";

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Hume, HumeClient } from 'hume';
import { VoiceConversationProps, Message } from './types';
import { VoiceConversationPropsSchema } from './schema';
import { v4 as uuidv4 } from 'uuid';

// Interface for the Web Speech API (not fully defined in TypeScript)
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

interface Window {
  SpeechRecognition: new () => SpeechRecognition;
  webkitSpeechRecognition: new () => SpeechRecognition;
}

const VoiceConversation: React.FC<VoiceConversationProps> = (props) => {
  // Validate input props with Zod
  const validatedProps = VoiceConversationPropsSchema.parse(props);
  
  const {
    apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY || '',
    generationId = '795c949a-1510-4a80-9646-7d0863b023ab',
    voiceName = 'David Hume',
    initialMessage = 'Hello! How can I assist you today?',
    placeholder = 'Type your message here...',
    speechRecognitionLang = 'ja-JP',
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
  
  // Speech recognition states
  const [isListening, setIsListening] = useState(false);
  const [isSpeechSupported, setIsSpeechSupported] = useState(false);
  const recognitionRef = useRef<SpeechRecognition | null>(null);

  // Check if speech recognition is supported
  useEffect(() => {
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    setIsSpeechSupported(!!SpeechRecognition);
    
    if (SpeechRecognition) {
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
          
          setInputValue(transcript);
        };
        
        recognitionRef.current.onerror = (event) => {
          console.error('Speech recognition error', event);
          setIsListening(false);
        };
        
        recognitionRef.current.onend = () => {
          setIsListening(false);
        };
      }
    }
    
    return () => {
      if (recognitionRef.current) {
        recognitionRef.current.abort();
      }
    };
  }, [speechRecognitionLang]);

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
  const toggleListening = () => {
    if (!recognitionRef.current) return;
    
    if (isListening) {
      recognitionRef.current.stop();
      setIsListening(false);
    } else {
      setInputValue('');
      recognitionRef.current.start();
      setIsListening(true);
    }
  };

  // Clean up audio URL objects when component unmounts
  useEffect(() => {
    return () => {
      if (audioUrl) {
        URL.revokeObjectURL(audioUrl);
      }
    };
  }, [audioUrl]);

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

      {/* Input form with voice input button */}
      <form onSubmit={handleSubmit} className="border-t p-4">
        <div className="flex">
          <input
            type="text"
            value={inputValue}
            onChange={handleInputChange}
            placeholder={isListening ? '🎤 Listening...' : placeholder}
            disabled={isLoading}
            className="flex-1 border rounded-l-md py-2 px-3 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          {isSpeechSupported && (
            <button
              type="button"
              onClick={toggleListening}
              disabled={isLoading}
              className={`px-3 border-t border-b ${
                isListening 
                  ? 'bg-red-500 text-white border-red-500' 
                  : 'bg-gray-100 text-gray-700 border-gray-300'
              }`}
              title={isListening ? 'Stop listening' : 'Start voice input'}
            >
              🎤
            </button>
          )}
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
        {isSpeechSupported && ' | Speech Recognition Available'}
      </div>
    </div>
  );
};

export default VoiceConversation;