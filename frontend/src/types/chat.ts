import type { Source } from "./source";

export type MessageRole = "user" | "assistant";

export interface ChatMessage {
    id: number;
    role: MessageRole;
    content: string;
    sources?: Source[];
}

export interface ChatRequest {
    question: string;
}