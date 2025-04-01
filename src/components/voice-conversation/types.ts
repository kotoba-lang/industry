export interface VoiceConversationProps {
  /**
   * Hume AI API key for TTS
   */
  apiKey?: string;
  
  /**
   * Generation ID for TTS
   */
  generationId?: string;
  
  /**
   * Name of the voice to use
   */
  voiceName?: string;
  
  /**
   * Initial message from the assistant
   */
  initialMessage?: string;
  
  /**
   * Placeholder text for the input field
   */
  placeholder?: string;
  
  /**
   * Language for speech recognition
   * Default: 'en-US'
   */
  speechRecognitionLang?: string;
  
  /**
   * Callback when user sends a message
   */
  onMessageSent?: (message: string) => void;
  
  /**
   * Callback when assistant message is received
   */
  onMessageReceived?: (message: string) => void;
  
  /**
   * Additional CSS classes
   */
  className?: string;
}

export interface Message {
  id: string;
  content: string;
  sender: 'user' | 'assistant';
  timestamp: Date;
}

export interface VoiceConversationState {
  messages: Message[];
  isLoading: boolean;
  error: string | null;
  inputValue: string;
}

export interface HumeVoice {
  id: string;
  name: string;
  isDefault: boolean;
} 