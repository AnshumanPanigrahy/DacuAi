import { useState } from "react";

import type {
    Document,
    DocumentSearchResult,
    DocumentStatistics,
} from "../types/document";
import Icon from "./Icon";

interface DocumentsPanelProps {
    documents: Document[];
    statistics: DocumentStatistics | null;
    isLoading: boolean;
    error: string;
    searchResults: DocumentSearchResult[] | null;
    isSearching: boolean;
    searchError: string;
    onSearch: (query: string, documentId?: string) => void;
    onDelete: (document: Document) => void;
    onAskFromDocument: (document: Document) => void;
    onRefresh: () => void;
}

function DocumentsPanel({
    documents,
    statistics,
    isLoading,
    error,
    searchResults,
    isSearching,
    searchError,
    onSearch,
    onDelete,
    onAskFromDocument,
    onRefresh,
}: DocumentsPanelProps) {
    const [query, setQuery] = useState("");
    const [selectedDocumentId, setSelectedDocumentId] = useState("");

    const handleSearch = (event: React.FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (query.trim()) {
            onSearch(query.trim(), selectedDocumentId || undefined);
        }
    };

    return (
        <section className="documents-panel" aria-label="Document library">
            <div className="documents-heading">
                <div>
                    <p className="eyebrow">Your library</p>
                    <h2>Documents</h2>
                    <p>Browse your PDFs or search their extracted content.</p>
                </div>
                <button
                    className="refresh-documents"
                    type="button"
                    onClick={onRefresh}
                    disabled={isLoading}
                >
                    <span aria-hidden="true">↻</span>
                    Refresh
                </button>
            </div>

            <div className="document-stats">
                <article className="stat-card">
                    <span className="stat-label">Documents</span>
                    <strong>{statistics?.totalDocuments ?? (isLoading ? "…" : documents.length)}</strong>
                    <span className="stat-caption">PDF files in your library</span>
                </article>
                {/*<article className="stat-card">*/}
                {/*    <span className="stat-label">Text chunks</span>*/}
                {/*    <strong>{statistics?.totalChunks ?? "—"}</strong>*/}
                {/*    <span className="stat-caption">Searchable passages</span>*/}
                {/*</article>*/}
            </div>

            <form className="document-search" onSubmit={handleSearch}>
                <label className="document-search-input">
                    <Icon name="search" size={18} />
                    <input
                        value={query}
                        onChange={(event) => setQuery(event.target.value)}
                        placeholder="Search inside your PDFs..."
                        aria-label="Search inside your PDFs"
                    />
                </label>
                <select
                    value={selectedDocumentId}
                    onChange={(event) => setSelectedDocumentId(event.target.value)}
                    aria-label="Limit search to a document"
                >
                    <option value="">All documents</option>
                    {documents.map((document) => (
                        <option key={document.id} value={document.id}>
                            {document.fileName}
                        </option>
                    ))}
                </select>
                <button type="submit" disabled={!query.trim() || isSearching}>
                    {isSearching ? "Searching…" : "Search"}
                </button>
            </form>
            {searchError && <p className="panel-error" role="alert">{searchError}</p>}

            {searchResults !== null && (
                <div className="search-results">
                    <div className="panel-section-heading">
                        <h3>Search results</h3>
                        <span>{searchResults.length} found</span>
                    </div>
                    {searchResults.length === 0 ? (
                        <p className="panel-empty">No matching passages. Try a broader search.</p>
                    ) : (
                        <div className="search-result-list">
                            {searchResults.map((result, index) => (
                                <article
                                    className="search-result"
                                    key={`${result.documentId}-${result.chunkId}-${index}`}
                                >
                                    <div className="search-result-meta">
                                        <Icon name="document" size={15} />
                                        <strong>
                                            {documents.find((item) => item.id === result.documentId)
                                                ?.fileName ?? `Document ${result.documentId}`}
                                        </strong>
                                        <span>Chunk {result.chunkNumber || "—"}</span>
                                        <span>{Math.round(result.score * 100)}% match</span>
                                        {result.retrievalType && <span>{result.retrievalType}</span>}
                                    </div>
                                    <p>{result.text || "No excerpt was returned."}</p>
                                </article>
                            ))}
                        </div>
                    )}
                </div>
            )}

            <div className="panel-section-heading library-heading">
                <h3>All documents</h3>
                <span>{documents.length} total</span>
            </div>
            {error && <p className="panel-error" role="alert">{error}</p>}
            {isLoading ? (
                <p className="panel-empty">Loading your documents…</p>
            ) : documents.length === 0 ? (
                <div className="panel-empty library-empty">
                    <span className="empty-document-icon"><Icon name="document" size={23} /></span>
                    <strong>No documents uploaded yet</strong>
                    <span>Upload a PDF to make it searchable and ask questions.</span>
                </div>
            ) : (
                <div className="document-list">
                    {documents.map((document) => (
                        <article className="document-row" key={document.id}>
                            <span className="document-row-icon">
                                <Icon name="document" size={19} />
                            </span>
                            <div className="document-row-info">
                                <strong title={document.fileName}>{document.fileName}</strong>
                                <span>
                                    ID {document.id}
                                    {document.uploadTime
                                        ? ` · ${new Date(document.uploadTime).toLocaleDateString()}`
                                        : ""}
                                    {typeof document.chunkCount === "number"
                                        ? ` · ${document.chunkCount} chunks`
                                        : ""}
                                </span>
                            </div>
                            <button
                                className="ask-document"
                                type="button"
                                onClick={() => onAskFromDocument(document)}
                            >
                                Ask
                            </button>
                            <button
                                className="delete-document"
                                type="button"
                                onClick={() => onDelete(document)}
                                aria-label={`Delete ${document.fileName}`}
                                title="Delete document"
                            >
                                <Icon name="trash" size={17} />
                            </button>
                        </article>
                    ))}
                </div>
            )}
        </section>
    );
}

export default DocumentsPanel;
