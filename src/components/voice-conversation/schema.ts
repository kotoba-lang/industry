import { z } from 'zod';

export const MessageSchema = z.object({
  id: z.string().uuid(),
  content: z.string().min(1),
  sender: z.enum(['user', 'assistant']),
  timestamp: z.date(),
  emotion: z.object({
    primary: z.enum(['neutral', 'happy', 'sad', 'angry', 'fearful', 'disgusted', 'surprised']),
    confidence: z.number().min(0).max(1),
    secondary: z.enum(['neutral', 'happy', 'sad', 'angry', 'fearful', 'disgusted', 'surprised']).optional(),
    timestamp: z.date()
  }).optional()
});

export const VoiceConversationPropsSchema = z.object({
  apiKey: z.string().optional(),
  generationId: z.string().optional(),
  voiceName: z.string().optional(),
  initialMessage: z.string().optional(),
  placeholder: z.string().optional(),
  speechRecognitionLang: z.string().optional(),
  onMessageSent: z.function().args(z.string()).returns(z.void()).optional(),
  onMessageReceived: z.function().args(z.string()).returns(z.void()).optional(),
  className: z.string().optional(),
  emotionRecognition: z.boolean().optional(),
  onEmotionDetected: z.function().args(z.any()).returns(z.void()).optional(),
  storageEnabled: z.boolean().optional(),
  storageHandler: z.function().args(z.any()).returns(z.promise(z.boolean())).optional()
});

export const HumeVoiceSchema = z.object({
  id: z.string(),
  name: z.string(),
  isDefault: z.boolean()
});

export const EmotionDataSchema = z.object({
  primary: z.enum(['neutral', 'happy', 'sad', 'angry', 'fearful', 'disgusted', 'surprised']),
  confidence: z.number().min(0).max(1),
  secondary: z.enum(['neutral', 'happy', 'sad', 'angry', 'fearful', 'disgusted', 'surprised']).optional(),
  timestamp: z.date()
});

export const SpeechRecognitionResultSchema = z.object({
  transcript: z.string(),
  confidence: z.number().min(0).max(1),
  isFinal: z.boolean()
});

export const StorageResponseSchema = z.object({
  success: z.boolean(),
  messageId: z.string().optional(),
  error: z.string().optional()
});

export type ValidatedMessage = z.infer<typeof MessageSchema>;
export type ValidatedVoiceConversationProps = z.infer<typeof VoiceConversationPropsSchema>;
export type ValidatedHumeVoice = z.infer<typeof HumeVoiceSchema>;
export type ValidatedEmotionData = z.infer<typeof EmotionDataSchema>;
export type ValidatedSpeechRecognitionResult = z.infer<typeof SpeechRecognitionResultSchema>;
export type ValidatedStorageResponse = z.infer<typeof StorageResponseSchema>; 