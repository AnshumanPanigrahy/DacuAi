import { useRef, useState } from "react";
import Icon from "./Icon";

interface FileUploadProps {
    onUpload: (file: File, signal: AbortSignal) => Promise<void>;
    disabled?: boolean;
    variant?: "button" | "dropzone";
}

function FileUpload({
    onUpload,
    disabled = false,
    variant = "button",
}: FileUploadProps) {
    const inputRef = useRef<HTMLInputElement>(null);
    const uploadControllerRef = useRef<AbortController | null>(null);
    const [isUploading, setIsUploading] = useState(false);
    const [error, setError] = useState("");
    const [isDragging, setIsDragging] = useState(false);

    const uploadFile = async (file?: File) => {
        if (!file) {
            return;
        }

        if (
            !file.name.toLowerCase().endsWith(".pdf") ||
            (file.type && file.type !== "application/pdf")
        ) {
            setError("Please select a PDF file.");
            return;
        }

        setError("");
        const signature = new TextDecoder().decode(
            await file.slice(0, 5).arrayBuffer(),
        );
        if (signature !== "%PDF-") {
            setError("The selected file is not a valid PDF.");
            return;
        }

        const controller = new AbortController();
        uploadControllerRef.current = controller;
        setIsUploading(true);

        try {
            await onUpload(file, controller.signal);
        } catch (uploadError) {
            if (controller.signal.aborted) {
                return;
            }

            setError(
                uploadError instanceof Error
                    ? uploadError.message
                    : "Unable to upload the PDF."
            );
        } finally {
            if (uploadControllerRef.current === controller) {
                uploadControllerRef.current = null;
                setIsUploading(false);
            }
        }
    };

    const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        event.target.value = "";
        void uploadFile(file);
    };

    const handleDrop = (event: React.DragEvent<HTMLDivElement>) => {
        event.preventDefault();
        setIsDragging(false);
        if (!disabled && !isUploading) {
            void uploadFile(event.dataTransfer.files[0]);
        }
    };

    const handleCancel = () => {
        uploadControllerRef.current?.abort();
        uploadControllerRef.current = null;
        setIsUploading(false);
        setError("");
    };

    return (
        <div
            className={`file-upload ${variant === "dropzone" ? "file-upload-dropzone" : ""}`}
            onDragEnter={variant === "dropzone" ? (event) => {
                event.preventDefault();
                setIsDragging(true);
            } : undefined}
            onDragOver={variant === "dropzone" ? (event) => event.preventDefault() : undefined}
            onDragLeave={variant === "dropzone" ? (event) => {
                if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
                    setIsDragging(false);
                }
            } : undefined}
            onDrop={variant === "dropzone" ? handleDrop : undefined}
        >
            <input
                ref={inputRef}
                type="file"
                accept=".pdf,application/pdf"
                onChange={handleFileChange}
                disabled={disabled || isUploading}
                hidden
            />

            {isUploading ? (
                variant === "dropzone" ? (
                    <div className="dropzone-uploading">
                        <span className="upload-progress" role="status">
                            Uploading PDF…
                        </span>
                        <button
                            type="button"
                            className="cancel-upload-button"
                            onClick={handleCancel}
                        >
                            Cancel
                        </button>
                    </div>
                ) : (
                    <>
                        <span className="upload-progress" role="status">
                            Uploading PDF…
                        </span>
                        <button
                            type="button"
                            className="cancel-upload-button"
                            onClick={handleCancel}
                        >
                            Cancel
                        </button>
                    </>
                )
            ) : variant === "dropzone" ? (
                <button
                    type="button"
                    className={`upload-dropzone${isDragging ? " dragging" : ""}`}
                    onClick={() => inputRef.current?.click()}
                    disabled={disabled}
                >
                    <span className="dropzone-icon" aria-hidden="true">
                        <Icon name="upload" size={20} />
                    </span>
                    <span className="dropzone-title">Drop your PDF here</span>
                    <span className="dropzone-copy">or <span className="dropzone-link">browse files</span> · PDF up to your server limit</span>
                </button>
            ) : (
                <button
                    type="button"
                    className="upload-button"
                    onClick={() => inputRef.current?.click()}
                    disabled={disabled}
                    title="Upload a PDF document"
                >
                    <Icon name="plus" size={15} />
                    <span>Upload PDF</span>
                </button>
            )}

            {error && (
                <p className="upload-error" role="alert">
                    {error}
                </p>
            )}
        </div>
    );
}

export default FileUpload;