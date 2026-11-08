import { useCallback, useEffect, useRef, useState } from "react";

import "./index.css";

import ChatInput from "./components/ChatInput";
import ChatWindow from "./components/ChatWindow";
import DocumentsPanel from "./components/DocumentsPanel";
import FileUpload from "./components/FileUpload";
import Icon from "./components/Icon";
import PdfViewerModal from "./components/PdfViewerModal";
import Sidebar from "./components/Sidebar";
import { streamChat } from "./services/chat.service";
import {
    createConversationId,
    loadConversationWorkspace,
    saveConversationWorkspace,
    upsertConversation,
    type SavedConversation,
} from "./services/conversation-storage.service";
import {
    deleteDocument,
    getDocuments,
    getDocumentStatistics,
    searchDocuments,
    uploadDocument,
} from "./services/document.service";
import type { ChatMessage } from "./types/chat";
import type {
    Document,
    DocumentSearchResult,
    DocumentStatistics,
} from "./types/document";

function App() {
    const [activeView, setActiveView] = useState<"chat" | "documents">("chat");
    const [initialWorkspace] = useState(loadConversationWorkspace);
    const [conversations, setConversations] = useState<SavedConversation[]>(
        initialWorkspace.conversations,
    );
    const [activeConversationId, setActiveConversationId] = useState<string | null>(
        initialWorkspace.activeConversation?.id ?? null,
    );
    const [messages, setMessages] = useState<ChatMessage[]>(
        initialWorkspace.activeConversation?.messages ?? [],
    );
    const [pdfViewer, setPdfViewer] = useState<{
        documentId: string;
        pageNumber: number;
    } | null>(null);
    const [isLoading, setIsLoading] = useState(false);
    const [uploadStatus, setUploadStatus] = useState("");
    const [uploadedFileName, setUploadedFileName] = useState<string | null>(null);
    const [isUploading, setIsUploading] = useState(false);
    const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
    const [draftMessage, setDraftMessage] = useState("");
    const [storageError, setStorageError] = useState("");
    const [documents, setDocuments] = useState<Document[]>([]);
    const [selectedDocument, setSelectedDocument] = useState<Document | null>(null);
    const [documentStatistics, setDocumentStatistics] =
        useState<DocumentStatistics | null>(null);
    const [isDocumentsLoading, setIsDocumentsLoading] = useState(false);
    const [documentsError, setDocumentsError] = useState("");
    const [searchResults, setSearchResults] = useState<DocumentSearchResult[] | null>(null);
    const [isSearchingDocuments, setIsSearchingDocuments] = useState(false);
    const [documentSearchError, setDocumentSearchError] = useState("");
    const [documentActionStatus, setDocumentActionStatus] = useState("");
    const conversationsRef = useRef(conversations);
    const chatControllerRef = useRef<AbortController | null>(null);
    const activeAssistantIdRef = useRef<number | null>(null);

    const refreshDocuments = useCallback(async () => {
        setIsDocumentsLoading(true);
        setDocumentsError("");

        const [documentsResult, statisticsResult] = await Promise.allSettled([
            getDocuments(),
            getDocumentStatistics(),
        ]);

        if (documentsResult.status === "fulfilled") {
            setDocuments(documentsResult.value);
        } else {
            setDocumentsError(
                documentsResult.reason instanceof Error
                    ? documentsResult.reason.message
                    : "Unable to load documents.",
            );
        }

        if (statisticsResult.status === "fulfilled") {
            setDocumentStatistics(statisticsResult.value);
        } else {
            setDocumentsError((previous) =>
                [
                    previous,
                    statisticsResult.reason instanceof Error
                        ? `Statistics: ${statisticsResult.reason.message}`
                        : "Unable to load document statistics.",
                ]
                    .filter(Boolean)
                    .join(" "),
            );
        }

        setIsDocumentsLoading(false);
    }, []);

    useEffect(() => {
        void Promise.resolve().then(refreshDocuments);
    }, [refreshDocuments]);

    const persistConversations = (
        nextConversations: SavedConversation[],
        activeId: string | null,
    ) => {
        conversationsRef.current = nextConversations;
        setConversations(nextConversations);
        try {
            saveConversationWorkspace(nextConversations, activeId);
            setStorageError("");
        } catch (error) {
            console.error("Unable to save recent conversations:", error);
            setStorageError("Recent conversations could not be saved in this browser.");
        }
    };

    const saveCurrentConversation = () => {
        if (!activeConversationId || messages.length === 0) {
            return;
        }

        const updatedConversations = upsertConversation(
            conversations,
            activeConversationId,
            messages,
        );
        persistConversations(updatedConversations, activeConversationId);
    };

    const abortCurrentChat = () => {
        chatControllerRef.current?.abort();
        chatControllerRef.current = null;
        activeAssistantIdRef.current = null;
        setIsLoading(false);
    };

    const handleCancelChat = () => {
        const assistantId = activeAssistantIdRef.current;
        abortCurrentChat();
        if (assistantId !== null) {
            setMessages((previous) =>
                previous.map((message) =>
                    message.id === assistantId
                        ? { ...message, content: "Response cancelled." }
                        : message,
                ),
            );
        }
        setIsLoading(false);
    };

    const handleNewChat = () => {
        saveCurrentConversation();
        abortCurrentChat();
        const newConversationId = createConversationId();
        persistConversations(conversationsRef.current, newConversationId);
        setActiveConversationId(newConversationId);
        setMessages([]);
    };

    const handleSelectConversation = (id: string) => {
        if (id === activeConversationId) {
            return;
        }

        saveCurrentConversation();
        abortCurrentChat();
        const conversation = conversationsRef.current.find((item) => item.id === id);
        if (!conversation) {
            return;
        }

        persistConversations(conversationsRef.current, conversation.id);
        setActiveConversationId(conversation.id);
        setMessages(conversation.messages);
    };

    const handleDeleteConversation = (id: string) => {
        const nextConversations = conversationsRef.current.filter(
            (conversation) => conversation.id !== id,
        );
        const isDeletingActive = id === activeConversationId;
        const nextActiveConversation = isDeletingActive ? nextConversations[0] ?? null : null;

        if (isDeletingActive) {
            abortCurrentChat();
            setActiveConversationId(nextActiveConversation?.id ?? null);
            setMessages(nextActiveConversation?.messages ?? []);
        }

        persistConversations(
            nextConversations,
            isDeletingActive ? nextActiveConversation?.id ?? null : activeConversationId,
        );
    };

    const handleSend = async (question: string) => {
        if (isLoading) {
            return;
        }

        const userMessage: ChatMessage = {
            id: Date.now(),
            role: "user",
            content: question,
        };

        const assistantMessageId = Date.now() + 1;
        const assistantMessage: ChatMessage = {
            id: assistantMessageId,
            role: "assistant",
            content: "",
        };
        setMessages((previous) => [...previous, userMessage, assistantMessage]);
        setIsLoading(true);
        if (!activeConversationId) {
            const newConversationId = createConversationId();
            persistConversations(conversationsRef.current, newConversationId);
            setActiveConversationId(newConversationId);
        }
        const controller = new AbortController();
        chatControllerRef.current = controller;
        activeAssistantIdRef.current = assistantMessageId;

        await streamChat(question, selectedDocument?.id ?? null, {
            onToken: (token) => {
                setMessages((previous) =>
                    previous.map((message) =>
                        message.id === assistantMessageId
                            ? { ...message, content: message.content + token }
                            : message,
                    ),
                );
            },
            onSources: (newSources) => {
                setMessages((previous) =>
                    previous.map((message) =>
                        message.id === assistantMessageId &&
                        message.role === "assistant"
                            ? { ...message, sources: newSources }
                            : message,
                    ),
                );
            },
            onComplete: () => {
                if (chatControllerRef.current === controller) {
                    chatControllerRef.current = null;
                    activeAssistantIdRef.current = null;
                    setIsLoading(false);
                }
            },
            onError: (error) => {
                console.error("Chat error:", error);
                setMessages((previous) =>
                    previous.map((message) =>
                        message.id === assistantMessageId
                            ? {
                                  ...message,
                                  content:
                                      "Sorry, something went wrong while processing your question.",
                              }
                            : message,
                    ),
                );
                if (chatControllerRef.current === controller) {
                    chatControllerRef.current = null;
                    activeAssistantIdRef.current = null;
                    setIsLoading(false);
                }
            },
        }, controller.signal);
    };

    const fillPrompt = (prompt: string) => {
        setDraftMessage(prompt);
        setActiveView("chat");
    };

    const handleAskFromDocument = (document: Document) => {
        setSelectedDocument(document);
        setActiveView("chat");
        setDraftMessage("");
    };

    const handleDocumentSearch = async (query: string, documentId?: string) => {
        setIsSearchingDocuments(true);
        setDocumentSearchError("");
        try {
            const results = await searchDocuments(query, documentId);
            setSearchResults(results);
        } catch (error) {
            setSearchResults(null);
            setDocumentSearchError(
                error instanceof Error ? error.message : "Document search failed.",
            );
        } finally {
            setIsSearchingDocuments(false);
        }
    };

    const handleDeleteDocument = async (document: Document) => {
        if (!window.confirm(`Delete "${document.fileName}" from your library?`)) {
            return;
        }

        setDocumentActionStatus(`Deleting ${document.fileName}...`);
        try {
            const result = await deleteDocument(document.id);
            setDocumentActionStatus(result || `${document.fileName} deleted.`);
            setSearchResults(null);
            await refreshDocuments();
        } catch (error) {
            setDocumentActionStatus(
                error instanceof Error ? error.message : "Unable to delete the document.",
            );
        }
    };

    useEffect(() => {
        if (!activeConversationId || messages.length === 0) {
            return;
        }

        const updatedConversations = upsertConversation(
            conversationsRef.current,
            activeConversationId,
            messages,
        );
        conversationsRef.current = updatedConversations;
        setConversations(updatedConversations);
        try {
            saveConversationWorkspace(updatedConversations, activeConversationId);
        } catch (error) {
            console.error("Unable to save recent conversations:", error);
        }
    }, [activeConversationId, messages]);

    const handleUpload = async (file: File, signal: AbortSignal) => {
        setIsUploading(true);
        setUploadStatus(`Uploading ${file.name}...`);

        try {
            const chunks = await uploadDocument(file, signal);
            setUploadedFileName(file.name);
            setUploadStatus(
                `${file.name} uploaded · ${chunks.length} text chunks extracted`,
            );
            setDocumentActionStatus("");
            await refreshDocuments();
        } catch (error) {
            if (signal.aborted) {
                setUploadStatus("Upload cancelled.");
                return;
            }
            setUploadStatus(
                error instanceof Error ? error.message : "PDF upload failed.",
            );
            throw error;
        } finally {
            setIsUploading(false);
        }
    };

    return (
        <div className={`app${isSidebarCollapsed ? " sidebar-collapsed" : ""}`}>
            <Sidebar
                onNewChat={handleNewChat}
                activeView={activeView}
                onSelectView={(view) => setActiveView(view)}
                conversations={conversations}
                activeConversationId={activeConversationId}
                onSelectConversation={handleSelectConversation}
                onDeleteConversation={handleDeleteConversation}
                collapsed={isSidebarCollapsed}
                onToggleCollapsed={() => setIsSidebarCollapsed((collapsed) => !collapsed)}
            />

            <main className="main">
                <header className="topbar">
                    <div className="header-title">
                        <div className="header-title-row">
                            <h1>
                                {activeView === "documents" ? "Document library" : "Document Assistant"}
                            </h1>
                            <div
                                className={`status${isLoading ? " status-loading" : ""}`}
                                role="status"
                                aria-live="polite"
                            >
                                <span className="status-dot" aria-hidden="true" />
                                {isLoading ? "Thinking..." : "AI ready"}
                            </div>
                        </div>
                        <p>
                            {activeView === "documents"
                                ? "Search, review, and manage your uploaded PDFs"
                                : "Ask questions and get answers grounded in your documents"}
                        </p>
                    </div>

                    <div className="topbar-actions">
                        <button
                            className="topbar-view-toggle"
                            type="button"
                            onClick={() =>
                                setActiveView((view) =>
                                    view === "chat" ? "documents" : "chat",
                                )
                            }
                        >
                            <Icon
                                name={activeView === "chat" ? "document" : "chat"}
                                size={15}
                            />
                            <span>{activeView === "chat" ? "Documents" : "Chat"}</span>
                        </button>
                        <button
                            className="topbar-new-chat"
                            type="button"
                            onClick={handleNewChat}
                        >
                            <Icon name="plus" size={14} />
                            <span>New</span>
                        </button>
                        {conversations.length > 0 && (
                            <>
                                <select
                                    className="mobile-recent-select"
                                    aria-label="Open a recent conversation"
                                    value={
                                        conversations.some(
                                            (conversation) =>
                                                conversation.id === activeConversationId,
                                        )
                                            ? activeConversationId ?? ""
                                            : ""
                                    }
                                    onChange={(event) => {
                                        if (event.target.value) {
                                            handleSelectConversation(event.target.value);
                                        }
                                    }}
                                >
                                    <option value="">Recent chats</option>
                                    {conversations.map((conversation) => (
                                        <option key={conversation.id} value={conversation.id}>
                                            {conversation.title}
                                        </option>
                                    ))}
                                </select>
                                <button
                                    className="mobile-delete-chat"
                                    type="button"
                                    onClick={() => {
                                        if (activeConversationId) {
                                            handleDeleteConversation(activeConversationId);
                                        }
                                    }}
                                    disabled={
                                        !conversations.some(
                                            (conversation) =>
                                                conversation.id === activeConversationId,
                                        )
                                    }
                                    aria-label="Delete current conversation"
                                    title="Delete current conversation"
                                >
                                    <Icon name="trash" size={15} />
                                </button>
                            </>
                        )}
                        {uploadStatus && (
                            <span className="upload-status" role="status">
                                {uploadStatus}
                            </span>
                        )}
                        <FileUpload
                            onUpload={handleUpload}
                            disabled={isUploading}
                        />
                    </div>
                </header>

                {activeView === "documents" ? (
                    <>
                        {documentActionStatus && (
                            <p className="document-action-status" role="status">
                                {documentActionStatus}
                            </p>
                        )}
                        <DocumentsPanel
                            documents={documents}
                            statistics={documentStatistics}
                            isLoading={isDocumentsLoading}
                            error={documentsError}
                            searchResults={searchResults}
                            isSearching={isSearchingDocuments}
                            searchError={documentSearchError}
                            onSearch={handleDocumentSearch}
                            onDelete={handleDeleteDocument}
                            onAskFromDocument={handleAskFromDocument}
                            onRefresh={() => void refreshDocuments()}
                        />
                    </>
                ) : (
                    <>
                        <ChatWindow
                            messages={messages}
                            onPrompt={fillPrompt}
                            isLoading={isLoading}
                            isUploading={isUploading}
                            uploadedFileName={uploadedFileName}
                            documentCount={
                                documentStatistics?.totalDocuments ?? documents.length
                            }
                            onUpload={handleUpload}
                            onSelectCitation={(documentId, pageNumber) =>
                                setPdfViewer({ documentId, pageNumber })
                            }
                        />

                        <div className="chat-composer">
                            <div
                                className="composer-chips"
                                aria-label="Prompt shortcuts and document context"
                            >
                                <span className="context-chip">
                                    <Icon name="document" size={14} />
                                    PDF ·{" "}
                                    {selectedDocument?.fileName ??
                                        uploadedFileName ??
                                        `${documentStatistics?.totalDocuments ?? documents.length} in library`}
                                </span>
                                {selectedDocument && (
                                    <button
                                        className="clear-context"
                                        type="button"
                                        onClick={() => setSelectedDocument(null)}
                                        disabled={isLoading}
                                    >
                                        Clear PDF
                                    </button>
                                )}
                                <button
                                    className="prompt-chip"
                                    type="button"
                                    onClick={() => fillPrompt("Summarize my document")}
                                    disabled={isLoading}
                                >
                                    Summary
                                </button>
                                <button
                                    className="prompt-chip"
                                    type="button"
                                    onClick={() =>
                                        fillPrompt(
                                            "Find detailed evidence and key points in my document",
                                        )
                                    }
                                    disabled={isLoading}
                                >
                                    Deep search
                                </button>
                            </div>
                            <ChatInput
                                value={draftMessage}
                                onChange={setDraftMessage}
                                onSend={handleSend}
                                onCancel={handleCancelChat}
                                isLoading={isLoading}
                            />
                            <p className="composer-hint">
                                DocuMind can make mistakes. Check important details in your
                                sources.
                            </p>
                        </div>
                    </>
                )}
                {storageError && (
                    <p className="storage-error" role="alert">
                        {storageError}
                    </p>
                )}
            </main>
            {pdfViewer && (
                <PdfViewerModal
                    documentId={pdfViewer.documentId}
                    pageNumber={pdfViewer.pageNumber}
                    onClose={() => setPdfViewer(null)}
                />
            )}
        </div>
    );
}

export default App;
