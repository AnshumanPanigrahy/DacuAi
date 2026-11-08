import type {
    Document,
    DocumentSearchResult,
    DocumentStatistics,
} from "../types/document";
import { API_BASE_URL, API_CREDENTIALS } from "./api";

function normalizeDocument(value: unknown): Document {
    if (typeof value !== "object" || value === null) {
        throw new Error("The documents response contains an invalid item.");
    }

    const raw = value as Record<string, unknown>;
    const rawId = raw.id ?? raw.documentId;
    const rawName =
        raw.fileName ??
        raw.filename ??
        raw.originalFilename ??
        raw.originalFileName ??
        raw.documentName ??
        raw.name ??
        raw.title;
    const rawUploadTime = raw.uploadTime ?? raw.uploadedAt ?? raw.createdAt;
    const rawChunkCount = raw.chunkCount ?? raw.totalChunks;

    if (
        (typeof rawId !== "string" && typeof rawId !== "number") ||
        typeof rawName !== "string"
    ) {
        throw new Error("A document is missing its identifier or filename.");
    }

    return {
        id: String(rawId),
        fileName: rawName,
        ...(typeof rawUploadTime === "string" ? { uploadTime: rawUploadTime } : {}),
        ...(typeof rawChunkCount === "number" ? { chunkCount: rawChunkCount } : {}),
    };
}

async function readError(response: Response): Promise<string> {
    const message = await response.text();
    return message || `Request failed: HTTP ${response.status}`;
}

export async function getDocumentPdf(documentId: string): Promise<Blob> {
    const response = await fetch(
        `${API_BASE_URL}/api/pdf/document/${encodeURIComponent(documentId)}/file`,
        { credentials: API_CREDENTIALS },
    );
    if (!response.ok) {
        throw new Error(await readError(response));
    }
    return response.blob();
}

export async function getDocuments(): Promise<Document[]> {
    const response = await fetch(`${API_BASE_URL}/api/pdf/documents`, {
        credentials: API_CREDENTIALS,
    });

    if (!response.ok) {
        throw new Error(await readError(response));
    }

    const result: unknown = await response.json();
    if (!Array.isArray(result)) {
        throw new Error("The documents endpoint did not return a list.");
    }
    return result.map(normalizeDocument);
}

export async function uploadDocument(
    file: File,
    signal?: AbortSignal,
): Promise<string[]> {
    const formData = new FormData();

    formData.append("file", file);

    const response = await fetch(
        `${API_BASE_URL}/api/pdf/upload`,
        {
            method: "POST",
            body: formData,
            signal,
            credentials: API_CREDENTIALS,
        }
    );

    if (!response.ok) {
        throw new Error(await readError(response));
    }

    const responseText = await response.text();
    if (!responseText) {
        return [];
    }

    let result: unknown;
    try {
        result = JSON.parse(responseText);
    } catch {
        throw new Error("The upload endpoint returned invalid JSON.");
    }

    if (!Array.isArray(result) || !result.every((chunk) => typeof chunk === "string")) {
        throw new Error("The upload endpoint did not return a list of text chunks.");
    }

    return result;
}

export async function getDocumentStatistics(): Promise<DocumentStatistics> {
    const response = await fetch(`${API_BASE_URL}/api/pdf/statistics`, {
        credentials: API_CREDENTIALS,
    });
    if (!response.ok) {
        throw new Error(await readError(response));
    }

    const result: unknown = await response.json();
    if (typeof result !== "object" || result === null) {
        throw new Error("The statistics endpoint returned invalid data.");
    }

    const stats = result as Record<string, unknown>;
    if (
        typeof stats.totalDocuments !== "number" ||
        typeof stats.totalChunks !== "number"
    ) {
        throw new Error("Document statistics are missing required counts.");
    }

    return {
        totalDocuments: stats.totalDocuments,
        totalChunks: stats.totalChunks,
    };
}

export async function searchDocuments(
    query: string,
    documentId?: string,
): Promise<DocumentSearchResult[]> {
    const params = new URLSearchParams({ query });
    const path = documentId
        ? `/api/pdf/search/document?${params.toString()}&documentId=${encodeURIComponent(documentId)}`
        : `/api/pdf/search?${params.toString()}`;
    const response = await fetch(`${API_BASE_URL}${path}`, {
        credentials: API_CREDENTIALS,
    });
    if (!response.ok) {
        throw new Error(await readError(response));
    }

    const result: unknown = await response.json();
    if (!Array.isArray(result)) {
        throw new Error("The document search endpoint did not return a list.");
    }
    return result.map((value) => {
        if (typeof value !== "object" || value === null) {
            throw new Error("The search response contains an invalid result.");
        }

        const item = value as Record<string, unknown>;
        if (
            typeof item.documentId !== "string" &&
            typeof item.documentId !== "number"
        ) {
            throw new Error("A search result is missing its document identifier.");
        }

        return {
            score: typeof item.score === "number" ? item.score : 0,
            documentId: String(item.documentId),
            chunkId: String(item.chunkId ?? ""),
            chunkNumber: String(item.chunkNumber ?? ""),
            text: typeof item.text === "string" ? item.text : "",
            retrievalType:
                typeof item.retrievalType === "string" ? item.retrievalType : "",
        };
    });
}

export async function deleteDocument(documentId: string): Promise<string> {
    const response = await fetch(
        `${API_BASE_URL}/api/pdf/document/${encodeURIComponent(documentId)}`,
        { method: "DELETE", credentials: API_CREDENTIALS },
    );
    if (!response.ok) {
        throw new Error(await readError(response));
    }
    return response.text();
}