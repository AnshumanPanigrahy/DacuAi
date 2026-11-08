package com.model.documindai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

@Service
public class PdfStorageService {

    private static final byte[] PDF_SIGNATURE = {
            '%', 'P', 'D', 'F', '-'
    };
    private static final int MAX_HEADER_OFFSET = 1024;
    private static final Pattern STORAGE_KEY_PATTERN =
            Pattern.compile("document-[0-9]+\\.pdf");

    private final Path storageDirectory;

    public PdfStorageService(
            @Value("${documind.pdf.storage-dir:${user.home}/.documind/pdfs}")
            String storageDirectory) {
        this.storageDirectory =
                Path.of(storageDirectory)
                        .toAbsolutePath()
                        .normalize();
    }

    public String store(Long documentId, byte[] pdfBytes)
            throws IOException {
        validatePdf(pdfBytes);

        String storageKey = "document-" + documentId + ".pdf";
        Path target = resolveStoragePath(storageKey);

        Files.createDirectories(storageDirectory);

        boolean created = false;
        try (var output = Files.newOutputStream(
                target,
                new OpenOption[]{
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                })) {
            created = true;
            output.write(pdfBytes);
        } catch (IOException e) {
            if (created) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupException) {
                    e.addSuppressed(cleanupException);
                }
            }
            throw e;
        }

        return storageKey;
    }

    public byte[] load(String storageKey) throws IOException {
        Path path = resolveStoragePath(storageKey);

        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(path.toString());
        }

        return Files.readAllBytes(path);
    }

    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolveStoragePath(storageKey));
    }

    private Path resolveStoragePath(String storageKey) {
        if (storageKey == null
                || !STORAGE_KEY_PATTERN.matcher(storageKey).matches()) {
            throw new IllegalArgumentException(
                    "Invalid PDF storage key."
            );
        }

        Path path = storageDirectory.resolve(storageKey).normalize();
        if (!path.startsWith(storageDirectory)) {
            throw new IllegalArgumentException(
                    "PDF storage path is outside the storage directory."
            );
        }

        return path;
    }

    private void validatePdf(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length < PDF_SIGNATURE.length) {
            throw new IllegalArgumentException(
                    "Uploaded content is not a valid PDF."
            );
        }

        int lastHeaderOffset =
                Math.min(MAX_HEADER_OFFSET, pdfBytes.length - PDF_SIGNATURE.length);

        for (int offset = 0; offset <= lastHeaderOffset; offset++) {
            boolean matches = true;

            for (int index = 0; index < PDF_SIGNATURE.length; index++) {
                if (pdfBytes[offset + index] != PDF_SIGNATURE[index]) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                return;
            }
        }

        throw new IllegalArgumentException(
                "Uploaded content is not a valid PDF."
        );
    }
}
