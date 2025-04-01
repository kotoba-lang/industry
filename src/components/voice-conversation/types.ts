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
  
  /**
   * Enable emotion recognition
   * Default: false
   */
  emotionRecognition?: boolean;
  
  /**
   * Callback when emotions are detected
   */
  onEmotionDetected?: (emotion: EmotionData) => void;
  
  /**
   * Enable database storage of conversations
   * Default: false
   */
  storageEnabled?: boolean;
  
  /**
   * Custom storage handler for conversations
   * If provided, overrides the default storage mechanism
   */
  storageHandler?: (message: Message) => Promise<boolean>;
}

export interface Message {
  id: string;
  content: string;
  sender: 'user' | 'assistant';
  timestamp: Date;
  emotion?: EmotionData;
}

export interface VoiceConversationState {
  messages: Message[];
  isLoading: boolean;
  error: string | null;
  inputValue: string;
  isRecording: boolean;
  currentEmotion: EmotionData | null;
  storageStatus: 'idle' | 'saving' | 'success' | 'error';
}

export interface HumeVoice {
  id: string;
  name: string;
  isDefault: boolean;
}

export interface EmotionData {
  primary: EmotionType;
  confidence: number;
  secondary?: EmotionType;
  timestamp: Date;
}

export type EmotionType = 
  | 'neutral'
  | 'happy'
  | 'sad'
  | 'angry'
  | 'fearful'
  | 'disgusted'
  | 'surprised';

export interface SpeechRecognitionResult {
  transcript: string;
  confidence: number;
  isFinal: boolean;
}

export interface StorageResponse {
  success: boolean;
  messageId?: string;
  error?: string;
} 