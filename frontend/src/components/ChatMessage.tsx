import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

interface ChatMessageProps {
    role: "user" | "assistant";
    content: string;
    isStreaming?: boolean;
}

function ChatMessage({
    role,
    content,
    isStreaming = false,
}: ChatMessageProps) {
    const isUser = role === "user";

    return (
        <div className={`chat-message ${isUser ? "user-message" : "assistant-message"}`}>
            <div className="message-avatar" aria-hidden="true">
                {isUser ? "U" : "D"}
            </div>
            <div className="message-content">
                <div className="message-role">
                    {isUser ? "You" : "DocuMind"}
                    {!isUser && isStreaming && (
                        <span className="streaming-label">Working</span>
                    )}
                </div>
                <div className="message-text">
                    {isUser ? (
                        <div className="user-message-text">{content}</div>
                    ) : isStreaming ? (
                        <div className="thinking-indicator" role="status" aria-label="Preparing answer">
                            <span />
                            <span />
                            <span />
                        </div>
                    ) : (
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
                    )}
                </div>
            </div>
        </div>
    );
}

export default ChatMessage;
