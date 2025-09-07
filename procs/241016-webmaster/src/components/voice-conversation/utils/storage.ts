import { ConversationMessage } from "../types";

export interface ConversationStore {
    saveMessage: (
        message: ConversationMessage,
    ) => Promise<{ success: boolean; messageId: string }>;
    getConversation: (
        conversationId?: string,
    ) => Promise<ConversationMessage[]>;
}

/**
 * Creates a conversation store for IndexedDB operations
 * @returns An object with methods to interact with the conversation store
 */
export function createConversationStore(): ConversationStore {
    return {
        /**
         * Saves a message to the store
         * @param message The message to save
         * @returns A promise that resolves to an object with success status and message ID
         */
        saveMessage: async (
            message: ConversationMessage,
        ): Promise<{ success: boolean; messageId: string }> => {
            // Implementation would use IndexedDB here
            return { success: true, messageId: `message-${Date.now()}` };
        },

        /**
         * Gets all messages for a conversation
         * @param conversationId Optional conversation ID
         * @returns A promise that resolves to an array of conversation messages
         */
        getConversation: async (
            conversationId?: string,
        ): Promise<ConversationMessage[]> => {
            // Implementation would use IndexedDB here
            return [];
        },
    };
}
