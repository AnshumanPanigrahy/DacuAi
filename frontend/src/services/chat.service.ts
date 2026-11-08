import type { Source } from "../types/source";
import { API_BASE_URL, API_CREDENTIALS } from "./api";

interface StreamCallbacks {
    onToken: (token: string) => void;
    onSources: (sources: Source[]) => void;
    onComplete: () => void;
    onError: (error: Error) => void;
}

export async function streamChat(
    question: string,
    documentId: string | null,
    callbacks: StreamCallbacks,
    signal?: AbortSignal,
): Promise<void> {

    try {

        const response = await fetch(
            `${API_BASE_URL}/api/ask`,
            {
                method: "POST",

                headers: {
                    "Content-Type": "application/json",
                    "Accept": "text/event-stream",
                },

                body: JSON.stringify({
                    question,
                    ...(documentId ? { documentId: Number(documentId) } : {}),
                }),
                signal,
                credentials: API_CREDENTIALS,
            }
        );

        if (!response.ok) {
            throw new Error(
                `Request failed with status ${response.status}`
            );
        }

        if (!response.body) {
            throw new Error(
                "Streaming response body is not available."
            );
        }

        const reader =
            response.body.getReader();

        const decoder =
            new TextDecoder();

        let buffer = "";

        while (true) {

            const { value, done } =
                await reader.read();

            if (done) {
                break;
            }

            buffer += decoder.decode(
                value,
                { stream: true }
            );

            const events =
                buffer.split("\n\n");

            buffer =
                events.pop() ?? "";

            for (const event of events) {

                processSseEvent(
                    event,
                    callbacks
                );
            }
        }

        if (buffer.trim()) {
            processSseEvent(
                buffer,
                callbacks
            );
        }

        callbacks.onComplete();

    } catch (error) {
        if (signal?.aborted) {
            return;
        }

        callbacks.onError(
            error instanceof Error
                ? error
                : new Error("Unknown error occurred.")
        );
    }
}

function processSseEvent(
    event: string,
    callbacks: StreamCallbacks
) {

    const lines =
        event.split("\n");

    let eventName = "message";
    let data = "";

    for (const line of lines) {

        if (line.startsWith("event:")) {

            eventName =
                line
                    .substring(6)
                    .trim();

        } else if (line.startsWith("data:")) {

            data += line.substring(5).replace(/^ /, "");
        }
    }

    if (!data) {
        return;
    }

    if (eventName === "token") {

        callbacks.onToken(data);

        return;
    }

    if (eventName === "sources") {

        try {

            const sources =
                JSON.parse(data) as Source[];

            callbacks.onSources(sources);

        } catch {

            console.error(
                "Failed to parse sources:",
                data
            );
        }
    }
}