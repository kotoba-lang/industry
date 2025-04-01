"use client";

import React, { useState } from 'react';
import { VoiceConversation } from '@/components/voice-conversation';

export default function VoiceConversationDemo() {
  const [isConfigured, setIsConfigured] = useState<boolean>(false);
  const [lastMessageSent, setLastMessageSent] = useState<string>('');
  const [lastMessageReceived, setLastMessageReceived] = useState<string>('');

  const handleStart = () => {
    setIsConfigured(true);
  };

  const handleMessageSent = (message: string) => {
    setLastMessageSent(message);
  };

  const handleMessageReceived = (message: string) => {
    setLastMessageReceived(message);
  };

  const apiKeyExists = !!process.env.NEXT_PUBLIC_HUME_API_KEY;

  return (
    <div className="container mx-auto py-8 px-4">
      <h1 className="text-3xl font-bold mb-6">Voice Conversation Demo</h1>
      
      {!isConfigured ? (
        <div className="bg-white rounded-lg shadow-md p-6 mb-8 max-w-md mx-auto">
          <h2 className="text-xl font-semibold mb-4">Voice Conversation with Hume API</h2>
          <p className="mb-4 text-gray-700">
            This demo uses the Hume Text-to-Speech API to create a voice conversation experience.
          </p>
          
          {apiKeyExists ? (
            <div className="p-2 bg-green-100 text-green-800 rounded-md mb-4 text-sm">
              ✓ API key is set in environment variables
            </div>
          ) : (
            <div className="p-2 bg-yellow-100 text-yellow-800 rounded-md mb-4 text-sm">
              ⚠️ No API key detected. Demo will run in text-only mode without voice capabilities.
              <br />
              To enable voice, add NEXT_PUBLIC_HUME_API_KEY to your .env file.
            </div>
          )}
          
          <button
            onClick={handleStart}
            className="w-full bg-blue-600 text-white py-2 px-4 rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            Start Demo
          </button>
        </div>
      ) : (
        <>
          <div className="mb-8">
            <VoiceConversation
              onMessageSent={handleMessageSent}
              onMessageReceived={handleMessageReceived}
            />
          </div>
          
          <div className="bg-white rounded-lg shadow-md p-6 max-w-md mx-auto">
            <h2 className="text-xl font-semibold mb-4">Event Log</h2>
            <div className="space-y-4">
              <div>
                <h3 className="text-sm font-medium text-gray-700">Last Message Sent:</h3>
                <p className="mt-1 p-2 bg-gray-50 rounded-md">
                  {lastMessageSent || "No messages sent yet"}
                </p>
              </div>
              <div>
                <h3 className="text-sm font-medium text-gray-700">Last Message Received:</h3>
                <p className="mt-1 p-2 bg-gray-50 rounded-md">
                  {lastMessageReceived || "No messages received yet"}
                </p>
              </div>
            </div>
          </div>
          
          <div className="mt-8 text-center">
            <button
              onClick={() => setIsConfigured(false)}
              className="text-blue-600 hover:text-blue-800 underline"
            >
              Restart Demo
            </button>
          </div>
        </>
      )}
      
      <div className="mt-10 border-t pt-6 text-center text-sm text-gray-500">
        <p>
          Using Hume TTS API with environment variable: NEXT_PUBLIC_HUME_API_KEY
          <br />
          <a href="https://hume.ai/docs/api-reference" className="text-blue-600 hover:underline" target="_blank" rel="noopener noreferrer">
            Hume API Documentation
          </a> | <a href="https://github.com/tts/voices" className="text-blue-600 hover:underline" target="_blank" rel="noopener noreferrer">
            Voice Models
          </a>
        </p>
      </div>
    </div>
  );
} 