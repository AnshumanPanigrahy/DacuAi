interface ChatInputProps {
    value: string;
    onChange: (value: string) => void;
    onSend: (message: string) => void;
    onCancel: () => void;
    isLoading: boolean;
}

import Icon from "./Icon";

function ChatInput({ value, onChange, onSend, onCancel, isLoading }: ChatInputProps) {
    const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const trimmedMessage = value.trim();

        if (!trimmedMessage || isLoading) {
            return;
        }

        onSend(trimmedMessage);
        onChange("");
    };

    return (
        <form className="input-area" onSubmit={handleSubmit}>
            <input
                type="text"
                value={value}
                onChange={(event) => onChange(event.target.value)}
                placeholder="Ask a question about your documents..."
                aria-label="Ask a question about your documents"
                disabled={isLoading}
            />
            {isLoading ? (
                <button
                    className="cancel-response-button"
                    type="button"
                    onClick={onCancel}
                >
                    Cancel
                </button>
            ) : (
                <button
                    className="send-button"
                    type="submit"
                    aria-label="Send message"
                    disabled={!value.trim()}
                >
                    <Icon name="send" size={18} />
                </button>
            )}
        </form>
    );
}

export default ChatInput;
