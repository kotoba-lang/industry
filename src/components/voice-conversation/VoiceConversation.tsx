"use client";

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { HumeClient } from 'hume';
import { VoiceConversationProps, Message } from './types';
import { VoiceConversationPropsSchema } from './schema';
import { v4 as uuidv4 } from 'uuid';

const VoiceConversation: React.FC<VoiceConversationProps> = (props) => {
  // Validate input props with Zod
  const validatedProps = VoiceConversationPropsSchema.parse(props);
  
  const {
    apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY || '',
    generationId = '795c949a-1510-4a80-9646-7d0863b023ab',
    voiceName = 'David Hume',
    initialMessage = 'Hello! How can I assist you today?',
    placeholder = 'Type your message here...',
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

  // Initialize the Hume client
  useEffect(() => {
    try {
      if (!apiKey) {
        console.warn('No API key provided. Speech generation will be disabled.');
        setIsApiAvailable(false);
        setError('API key not provided. Speech functionality disabled.');
      } else {
        humeClientRef.current = new HumeClient({ apiKey });
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

  // Function to generate TTS audio
  const generateSpeech = useCallback(async (text: string): Promise<string | null> => {
    if (!humeClientRef.current || !isApiAvailable) {
      console.warn('Speech generation skipped: Client not available or API disabled');
      return null;
    }
    
    try {
      setIsLoading(true);
      
      // Create the voice if it doesn't exist (in a real app, you would check if it exists first)
      try {
        console.log('Attempting to create voice with name:', voiceName);
        await humeClientRef.current.tts.voices.create({
          generationId,
          name: voiceName,
        });
        console.log('Voice created or already exists');
      } catch (error: any) {
        // Voice might already exist, continue
        console.log('Voice creation error (might already exist):', error?.message || error);
      }
      
      // Generate speech from text
      console.log('Attempting to generate speech for text:', text);
      
      // Check for available methods and call the correct one
      let response;
      console.log('Available TTS methods:', Object.keys(humeClientRef.current.tts));
      
      if (typeof humeClientRef.current.tts.synthesize === 'function') {
        console.log('Using tts.synthesize method');
        response = await humeClientRef.current.tts.synthesize({
          text,
          voice: voiceName,
        });
      } else if (typeof humeClientRef.current.tts.generate === 'function') {
        console.log('Using tts.generate method');
        response = await humeClientRef.current.tts.generate({
          text,
          voice: voiceName,
        });
      } else if (typeof humeClientRef.current.tts.streamTts === 'function') {
        console.log('Using tts.streamTts method');
        response = await humeClientRef.current.tts.streamTts({
          text,
          voice: voiceName,
        });
      } else {
        // Try to find any method in the TTS object that might be for text-to-speech
        const potentialTtsMethods = Object.keys(humeClientRef.current.tts).filter(
          method => 
            typeof humeClientRef.current.tts[method] === 'function' && 
            !['voices', 'list', 'get', 'delete', 'create', 'update'].includes(method)
        );
        
        console.log('Potential TTS methods found:', potentialTtsMethods);
        
        if (potentialTtsMethods.length > 0) {
          // Try the first potential method
          const methodToTry = potentialTtsMethods[0];
          console.log(`Trying potential TTS method: ${methodToTry}`);
          
          response = await humeClientRef.current.tts[methodToTry]({
            text,
            voice: voiceName,
          });
        } else {
          throw new Error('No compatible TTS method found in Hume API client');
        }
      }
      
      console.log('Speech generated successfully, creating audio URL');
      // Convert the audio response to a URL
      let blob;
      
      // Check if response has arrayBuffer method
      if (response && typeof response.arrayBuffer === 'function') {
        blob = new Blob([await response.arrayBuffer()], { type: 'audio/mpeg' });
      } else if (response && response.audio) {
        // Some APIs might return audio data directly
        blob = new Blob([response.audio], { type: 'audio/mpeg' });
      } else if (response && typeof response === 'object') {
        // Log available properties to help debug
        console.log('Response properties:', Object.keys(response));
        throw new Error('Unsupported response format from TTS API');
      } else {
        throw new Error('Invalid response from TTS API');
      }
      
      const url = URL.createObjectURL(blob);
      return url;
    } catch (err: any) {
      const errorMessage = err?.message || 'Unknown error';
      console.error('Text-to-speech detailed error:', errorMessage);
      console.error('Error object:', JSON.stringify(err, null, 2));
      setError(`Failed to generate speech: ${errorMessage}`);
      return null;
    } finally {
      setIsLoading(false);
    }
  }, [generationId, voiceName, isApiAvailable]);

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

      {/* Input form */}
      <form onSubmit={handleSubmit} className="border-t p-4">
        <div className="flex">
          <input
            type="text"
            value={inputValue}
            onChange={handleInputChange}
            placeholder={placeholder}
            disabled={isLoading}
            className="flex-1 border rounded-l-md py-2 px-3 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
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
      </div>
    </div>
  );
};

export default VoiceConversation;