import type { ChatMessage } from "../types/chat";
export interface SavedConversation {
    id: string;
    title: string;
    messages: ChatMessage[];
    updatedAt: number;
}

export interface ConversationWorkspace {
    conversations: SavedConversation[];
    activeConversation: SavedConversation | null;
}

const CONVERSATIONS_KEY = "documind.recent-conversations";
const ACTIVE_CONVERSATION_KEY = "documind.active-conversation";
const MAX_RECENT_CONVERSATIONS = 12;

function isSavedConversation(value: unknown): value is SavedConversation {
    if (typeof value !== "object" || value === null) {
        return false;
    }

    const conversation = value as Partial<SavedConversation>;
    return (
        typeof conversation.id === "string" &&
        typeof conversation.title === "string" &&
        typeof conversation.updatedAt === "number"
    );
}

export function loadConversationWorkspace(): ConversationWorkspace {
    try {
        const stored = localStorage.getItem(CONVERSATIONS_KEY);
        const parsed: unknown = stored ? JSON.parse(stored) : [];
        const conversations = Array.isArray(parsed)
            ? parsed
                .filter(isSavedConversation)
                .map(({ id, updatedAt }) => ({
                    id,
                    title: "Conversation",
                    messages: [],
                    updatedAt,
                }))
                .sort((a, b) => b.updatedAt - a.updatedAt)
            : [];
        localStorage.setItem(
            CONVERSATIONS_KEY,
            JSON.stringify(
                conversations
                    .slice(0, MAX_RECENT_CONVERSATIONS)
                    .map(({ id, title, updatedAt }) => ({ id, title, updatedAt })),
            ),
        );
        const activeId = localStorage.getItem(ACTIVE_CONVERSATION_KEY);
        const activeConversation =
            conversations.find((conversation) => conversation.id === activeId) ??
            conversations[0] ??
            null;

        return {
            conversations: conversations.slice(0, MAX_RECENT_CONVERSATIONS),
            activeConversation,
        };
    } catch (error) {
        console.error("Unable to load recent conversations:", error);
        return { conversations: [], activeConversation: null };
    }
}

export function saveConversationWorkspace(
    conversations: SavedConversation[],
    activeId: string | null,
): void {
    const metadata = conversations.slice(0, MAX_RECENT_CONVERSATIONS).map(
        ({ id, title, updatedAt }) => ({ id, title, updatedAt }),
    );
    localStorage.setItem(
        CONVERSATIONS_KEY,
        JSON.stringify(metadata),
    );
    if (activeId) {
        localStorage.setItem(ACTIVE_CONVERSATION_KEY, activeId);
    } else {
        localStorage.removeItem(ACTIVE_CONVERSATION_KEY);
    }
}

export function upsertConversation(
    conversations: SavedConversation[],
    id: string,
    messages: ChatMessage[],
): SavedConversation[] {
    const updatedConversation: SavedConversation = {
        id,
        title: "Conversation",
        messages,
        updatedAt: Date.now(),
    };

    return [
        updatedConversation,
        ...conversations.filter((conversation) => conversation.id !== id),
    ]
        .sort((a, b) => b.updatedAt - a.updatedAt)
        .slice(0, MAX_RECENT_CONVERSATIONS);
}

export function createConversationId(): string {
    return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}
