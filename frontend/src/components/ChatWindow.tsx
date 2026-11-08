import ChatMessage from "./ChatMessage";
import FileUpload from "./FileUpload";
import Icon from "./Icon";
import SourceList from "./SourceList";

import type { ChatMessage as ChatMessageType } from "../types/chat";

interface ChatWindowProps {
    messages: ChatMessageType[];
    onPrompt: (prompt: string) => void;
    isLoading: boolean;
    isUploading: boolean;
    uploadedFileName: string | null;
    documentCount: number;
    onUpload: (file: File, signal: AbortSignal) => Promise<void>;
    onSelectCitation: (documentId: string, pageNumber: number) => void;
}

const prompts = [
    {
        icon: "summary" as const,
        title: "Summarize a document",
        description: "Get the key points at a glance",
        prompt: "Summarize my document",
    },
    {
        icon: "search" as const,
        title: "Find key topics",
        description: "Surface themes and important details",
        prompt: "What are the most important topics in my documents?",
    },
    {
        icon: "question" as const,
        title: "Ask a question",
        description: "Explore your documents in more depth",
        prompt: "What can you tell me about my document?",
    },
];

function ChatWindow({
    messages,
    onPrompt,
    isLoading,
    isUploading,
    uploadedFileName,
    documentCount,
    onUpload,
    onSelectCitation,
}: ChatWindowProps) {
    return (
        <section className="chat-area" aria-label="Document conversation">
            <div className="chat-window">
                {messages.length === 0 ? (
                    <div className="welcome">
                        <div className="welcome-icon" aria-hidden="true">
                            <Icon name="document" size={30} />
                        </div>
                        <p className="eyebrow">Your documents, made useful</p>
                        <h2>Ask your documents anything.</h2>
                        <p>
                            Upload a PDF, then ask a question. Get clear answers drawn
                            from the content you shared.
                        </p>

                        <FileUpload
                            onUpload={onUpload}
                            disabled={isLoading || isUploading}
                            variant="dropzone"
                        />

                        <div className="welcome-features" aria-label="Workspace features">
                            <span>
                                PDF · {uploadedFileName ?? `${documentCount} in library`}
                            </span>
                            <span>Source-backed answers</span>
                            <span>Streaming responses</span>
                        </div>

                        <div className="suggestions" aria-label="Suggested prompts">
                            {prompts.map((item) => (
                                <button
                                    key={item.title}
                                    type="button"
                                    onClick={() => onPrompt(item.prompt)}
                                    disabled={isLoading}
                                >
                                    <span className="suggestion-icon" aria-hidden="true">
                                        <Icon name={item.icon} size={19} />
                                    </span>
                                    <span>
                                        <strong>{item.title}</strong>
                                        <span>{item.description}</span>
                                    </span>
                                </button>
                            ))}
                        </div>
                    </div>
                ) : (
                    <div className="messages" aria-live="polite">
                        {messages.map((message) => {
                            const renderedMessage = (
                                <ChatMessage
                                    role={message.role}
                                    content={message.content}
                                    isStreaming={
                                        isLoading &&
                                        message.role === "assistant" &&
                                        message.id === messages[messages.length - 1]?.id &&
                                        !message.content
                                    }
                                />
                            );

                            return message.role === "assistant" ? (
                                <div className="message-with-sources" key={message.id}>
                                    {renderedMessage}
                                    <SourceList
                                        sources={message.sources ?? []}
                                        onSelectCitation={onSelectCitation}
                                    />
                                </div>
                            ) : (
                                <div key={message.id}>{renderedMessage}</div>
                            );
                        })}
                    </div>
                )}
            </div>
        </section>
    );
}

export default ChatWindow;
