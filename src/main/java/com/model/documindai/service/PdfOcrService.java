package com.model.documindai.service;

import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.awt.*;


@Service
public class PdfOcrService {

    private final Tesseract tesseract;
    private final OcrTextCleaner ocrTextCleaner;

    public PdfOcrService(
            OcrTextCleaner ocrTextCleaner) {

        this.ocrTextCleaner = ocrTextCleaner;

        tesseract = new Tesseract();

        tesseract.setDatapath(
                "C:\\Program Files\\Tesseract-OCR\\tessdata"
        );

        tesseract.setLanguage("eng");

        tesseract.setPageSegMode(11);

        tesseract.setVariable(
                "user_defined_dpi",
                "300"
        );
    }

    public String extractText(byte[] pdfBytes) {

        StringBuilder extractedText =
                new StringBuilder();

        try (PDDocument document =
                     Loader.loadPDF(pdfBytes)) {

            PDFRenderer renderer =
                    new PDFRenderer(document);

            int totalPages =
                    document.getNumberOfPages();

            for (int page = 0;
                 page < totalPages;
                 page++) {


                BufferedImage originalImage =
                        renderer.renderImageWithDPI(
                                page,
                                300,
                                ImageType.RGB
                        );

                // Preprocess image
                BufferedImage processedImage =
                        preprocessImage(originalImage);

                // OCR
                String pageText =
                        tesseract.doOCR(processedImage);

                pageText =
                        ocrTextCleaner.clean(pageText);

                if (pageText != null &&
                        !pageText.isBlank()) {

                    extractedText
                            .append(pageText.trim())
                            .append("\n\n");
                }
            }

        } catch (Exception e) {

            throw new RuntimeException(
                    "PDF OCR processing failed",
                    e
            );
        }

        return extractedText
                .toString()
                .trim();
    }

    private BufferedImage preprocessImage(
            BufferedImage original) {

        // --------------------------------------------------
        // 1. Convert to grayscale
        // --------------------------------------------------

        BufferedImage grayscale =
                new BufferedImage(
                        original.getWidth(),
                        original.getHeight(),
                        BufferedImage.TYPE_BYTE_GRAY
                );

        Graphics2D graphics =
                grayscale.createGraphics();

        graphics.drawImage(
                original,
                0,
                0,
                null
        );

        graphics.dispose();


        // --------------------------------------------------
        // 2. Increase contrast
        // --------------------------------------------------

        RescaleOp rescaleOp =
                new RescaleOp(
                        1.5f,
                        -40f,
                        null
                );

        BufferedImage enhanced =
                rescaleOp.filter(
                        grayscale,
                        null
                );


        // --------------------------------------------------
        // 3. Convert to black & white
        // --------------------------------------------------

        BufferedImage binary =
                new BufferedImage(
                        enhanced.getWidth(),
                        enhanced.getHeight(),
                        BufferedImage.TYPE_BYTE_BINARY
                );

        int width = enhanced.getWidth();
        int height = enhanced.getHeight();

        for (int y = 0; y < height; y++) {

            for (int x = 0; x < width; x++) {

                int gray =
                        enhanced
                                .getRaster()
                                .getSample(
                                        x,
                                        y,
                                        0
                                );

                /*
                 * Dark pixels = handwriting
                 * Light pixels = paper
                 */
                if (gray < 180) {

                    binary.setRGB(
                            x,
                            y,
                            Color.BLACK.getRGB()
                    );

                } else {

                    binary.setRGB(
                            x,
                            y,
                            Color.WHITE.getRGB()
                    );
                }
            }
        }

        return removeNoise(binary);
    }
    private BufferedImage removeNoise(
            BufferedImage image) {

        int width = image.getWidth();
        int height = image.getHeight();

        BufferedImage result =
                new BufferedImage(
                        width,
                        height,
                        BufferedImage.TYPE_BYTE_BINARY
                );

        for (int y = 1; y < height - 1; y++) {

            for (int x = 1; x < width - 1; x++) {

                int blackNeighbors = 0;

                for (int dy = -1; dy <= 1; dy++) {

                    for (int dx = -1; dx <= 1; dx++) {

                        if (dx == 0 && dy == 0) {
                            continue;
                        }

                        int rgb =
                                image.getRGB(
                                        x + dx,
                                        y + dy
                                );

                        if ((rgb & 0xFF) < 128) {
                            blackNeighbors++;
                        }
                    }
                }

                int current =
                        image.getRGB(x, y) & 0xFF;

                /*
                 * Remove isolated black pixels.
                 */
                if (current < 128 &&
                        blackNeighbors <= 1) {

                    result.setRGB(
                            x,
                            y,
                            Color.WHITE.getRGB()
                    );

                } else {

                    result.setRGB(
                            x,
                            y,
                            image.getRGB(x, y)
                    );
                }
            }
        }

        return result;
    }
}