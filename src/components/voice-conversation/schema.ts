import { z } from 'zod';

export const MessageSchema = z.object({
  id: z.string().uuid(),
  content: z.string().min(1),
  sender: z.enum(['user', 'assistant']),
  timestamp: z.date()
});

export const VoiceConversationPropsSchema = z.object({
  apiKey: z.string().optional(),
  generationId: z.string().optional(),
  voiceName: z.string().optional(),
  initialMessage: z.string().optional(),
  placeholder: z.string().optional(),
  onMessageSent: z.function().args(z.string()).optional(),
  onMessageReceived: z.function().args(z.string()).optional(),
  className: z.string().optional()
});

export const HumeVoiceSchema = z.object({
  id: z.string(),
  name: z.string(),
  isDefault: z.boolean()
});

export type ValidatedMessage = z.infer<typeof MessageSchema>;
export type ValidatedVoiceConversationProps = z.infer<typeof VoiceConversationPropsSchema>;
export type ValidatedHumeVoice = z.infer<typeof HumeVoiceSchema>; 