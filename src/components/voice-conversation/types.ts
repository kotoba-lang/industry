export interface VoiceConversationProps {
  apiKey?: string;
  generationId?: string;
  voiceName?: string;
  initialMessage?: string;
  placeholder?: string;
  onMessageSent?: (message: string) => void;
  onMessageReceived?: (message: string) => void;
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