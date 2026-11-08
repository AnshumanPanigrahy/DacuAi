import type { Source } from "../types/source";
import Icon from "./Icon";

interface SourceListProps {
    sources: Source[];
    onSelectCitation: (documentId: string, pageNumber: number) => void;
}

function SourceList({ sources, onSelectCitation }: SourceListProps) {
    if (sources.length === 0) {
        return null;
    }

    return (
        <div className="sources">
            <div className="sources-title">
                Sources
            </div>

            <div className="source-list">
                {sources.map((source) => {
                    const pageNumbers = Array.isArray(source.pageNumbers)
                        ? source.pageNumbers
                            .filter(
                            (pageNumber) =>
                                Number.isInteger(pageNumber) && pageNumber > 0,
                            )
                            .filter(
                                (pageNumber, index, pages) =>
                                    pages.indexOf(pageNumber) === index,
                            )
                            .sort((a, b) => a - b)
                        : [];
                    const pageLabel =
                        pageNumbers.length > 0
                            ? `${pageNumbers.length === 1 ? "Page" : "Pages"} ${pageNumbers.join(", ")}`
                            : null;

                    return (
                        <article
                            className="source-item"
                            key={`${source.documentId}-${source.chunkId}`}
                            tabIndex={0}
                            aria-label={`Source: Document ${source.documentId}, chunk ${source.chunkNumber}${pageLabel ? `, ${pageLabel}` : ""}`}
                        >
                            <span className="source-icon">
                                <Icon name="document" size={17} />
                            </span>

                            <div className="source-info">
                                <span className="source-label">Source</span>
                                <div className="source-document">
                                    Document {source.documentId}
                                </div>
                                <div className="source-chunk">
                                    Chunk {source.chunkNumber}
                                </div>
                                {pageNumbers.length > 0 && (
                                    <div className="source-pages">
                                        <span>
                                            {pageNumbers.length === 1 ? "Page " : "Pages "}
                                        </span>
                                        {pageNumbers.map((pageNumber, index) => (
                                            <span key={pageNumber}>
                                                {index > 0 && ", "}
                                                <button
                                                    className="source-page-button"
                                                    type="button"
                                                    onClick={() =>
                                                        onSelectCitation(
                                                            source.documentId,
                                                            pageNumber,
                                                        )
                                                    }
                                                    aria-label={`Open document ${source.documentId} at page ${pageNumber}`}
                                                >
                                                    {pageNumber}
                                                </button>
                                            </span>
                                        ))}
                                    </div>
                                )}
                            </div>
                        </article>
                    );
                })}
            </div>
        </div>
    );
}

export default SourceList;