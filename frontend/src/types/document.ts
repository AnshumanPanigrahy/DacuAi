export interface Document {
    id: string;
    fileName: string;
    uploadTime?: string;
    chunkCount?: number;
}

export interface DocumentStatistics {
    totalDocuments: number;
    totalChunks: number;
}

export interface DocumentSearchResult {
    score: number;
    documentId: string;
    chunkId: string;
    chunkNumber: string;
    text: string;
    retrievalType: string;
}