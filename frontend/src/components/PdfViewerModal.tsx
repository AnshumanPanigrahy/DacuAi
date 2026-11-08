import { useEffect, useState } from "react";

import { getDocumentPdf } from "../services/document.service";
import Icon from "./Icon";

interface PdfViewerModalProps {
    documentId: string;
    pageNumber: number;
    onClose: () => void;
}

type PdfLoadState =
    | { documentId: string; status: "loading" }
    | { documentId: string; status: "loaded"; url: string }
    | { documentId: string; status: "error"; message: string };

function PdfViewerModal({
    documentId,
    pageNumber,
    onClose,
}: PdfViewerModalProps) {
    const [loadState, setLoadState] = useState<PdfLoadState>({
        documentId,
        status: "loading",
    });

    useEffect(() => {
        let isCurrent = true;
        let objectUrl: string | null = null;

        void getDocumentPdf(documentId)
            .then((pdfBlob) => {
                if (!isCurrent) {
                    return;
                }

                objectUrl = URL.createObjectURL(pdfBlob);
                setLoadState({
                    documentId,
                    status: "loaded",
                    url: objectUrl,
                });
            })
            .catch((fetchError: unknown) => {
                if (isCurrent) {
                    setLoadState({
                        documentId,
                        status: "error",
                        message:
                            fetchError instanceof Error
                                ? fetchError.message
                                : "Unable to load this PDF.",
                    });
                }
            })

        return () => {
            isCurrent = false;
            if (objectUrl) {
                URL.revokeObjectURL(objectUrl);
            }
        };
    }, [documentId]);

    const isLoading =
        loadState.documentId !== documentId ||
        loadState.status === "loading";
    const error =
        loadState.documentId === documentId && loadState.status === "error"
            ? loadState.message
            : "";
    const pdfUrl =
        loadState.documentId === documentId && loadState.status === "loaded"
            ? loadState.url
            : null;

    return (
        <div className="pdf-viewer-backdrop">
            <section
                className="pdf-viewer-modal"
                role="dialog"
                aria-modal="true"
                aria-label={`Document ${documentId}, page ${pageNumber}`}
            >
                <header className="pdf-viewer-header">
                    <div>
                        <strong>Document {documentId}</strong>
                        <span>Page {pageNumber}</span>
                    </div>
                    <button
                        className="pdf-viewer-close"
                        type="button"
                        onClick={onClose}
                        aria-label="Close PDF viewer"
                    >
                        <Icon name="close" size={18} />
                    </button>
                </header>

                {isLoading && (
                    <div className="pdf-viewer-status" role="status">
                        Loading PDF…
                    </div>
                )}
                {error && (
                    <div className="pdf-viewer-error" role="alert">
                        {error}
                    </div>
                )}
                {pdfUrl && (
                    <iframe
                        className="pdf-viewer-frame"
                        src={`${pdfUrl}#page=${pageNumber}`}
                        title={`PDF document ${documentId}, page ${pageNumber}`}
                        sandbox="allow-scripts"
                        referrerPolicy="no-referrer"
                    />
                )}
            </section>
        </div>
    );
}

export default PdfViewerModal;
