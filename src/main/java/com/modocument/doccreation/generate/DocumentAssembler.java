package com.modocument.doccreation.generate;

import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.entity.DocImageAsset;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The reproduction engine: re-opens a fresh copy of the immutable blueprint
 * file and splices the user's paragraphs and images into their sections. No
 * original element is removed, restyled or rewritten — only new paragraphs are
 * inserted, so the generated document is the uploaded one, in that exact
 * manner, plus the added content.
 */
@Component
public class DocumentAssembler {

    /** Widest an inserted image may render, even on wide pages. */
    private static final double MAX_IMAGE_WIDTH_INCHES = 6.5;
    private static final int TWIPS_PER_INCH = 1440;

    /**
     * Assembles the final document.
     *
     * @param blueprintDocx    the stored blueprint file
     * @param sectionsInOrder  the blueprint's sections in document order
     * @param blocksBySectionId content blocks per section, each list already ordered
     * @param assetsById       image assets referenced by IMAGE blocks
     * @param assetBytesLoader resolves an asset's stored file to its bytes
     * @return the .docx bytes
     */
    public byte[] assemble(Path blueprintDocx,
                           List<DocBlueprintSection> sectionsInOrder,
                           Map<Integer, List<DocContentBlock>> blocksBySectionId,
                           Map<Integer, DocImageAsset> assetsById,
                           Function<Integer, byte[]> assetBytesLoader) {
        try (InputStream in = Files.newInputStream(blueprintDocx);
             XWPFDocument doc = new XWPFDocument(in)) {

            List<IBodyElement> elements = doc.getBodyElements();
            validateAnchors(sectionsInOrder, elements);

            // Resolve every section's insertion target BEFORE the first insert:
            // section i's content goes right before section i+1's heading (the
            // last and the implicit section append at the body end). Inserting
            // shifts body-element indexes, but these paragraph references stay
            // valid, so index lookups must all happen against the pristine doc.
            Map<Integer, XWPFParagraph> insertBeforeBySectionId = new HashMap<>();
            for (int i = 0; i < sectionsInOrder.size(); i++) {
                DocBlueprintSection section = sectionsInOrder.get(i);
                if (i + 1 < sectionsInOrder.size()) {
                    int nextAnchor = sectionsInOrder.get(i + 1).getAnchorParagraphIndex();
                    insertBeforeBySectionId.put(section.getDocBlueprintSectionId(),
                            (XWPFParagraph) elements.get(nextAnchor));
                }
            }

            int usableWidthEmu = usableWidthEmu(doc);
            for (DocBlueprintSection section : sectionsInOrder) {
                List<DocContentBlock> blocks =
                        blocksBySectionId.getOrDefault(section.getDocBlueprintSectionId(), List.of());
                XWPFParagraph insertBefore = insertBeforeBySectionId.get(section.getDocBlueprintSectionId());
                for (DocContentBlock block : blocks) {
                    if (DocContentBlock.TYPE_PARAGRAPH.equals(block.getBlockType())) {
                        writeParagraph(newParagraph(doc, insertBefore), block.getTextContent());
                    } else {
                        DocImageAsset asset = assetsById.get(block.getAssetId());
                        if (asset == null) {
                            throw new IllegalStateException(
                                    "Image block " + block.getDocContentBlockId() + " references a missing asset.");
                        }
                        byte[] bytes = assetBytesLoader.apply(asset.getDocImageAssetId());
                        writeImage(newParagraph(doc, insertBefore), asset, bytes, usableWidthEmu);
                        if (block.getCaption() != null && !block.getCaption().isBlank()) {
                            writeCaption(newParagraph(doc, insertBefore), block.getCaption());
                        }
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not assemble the document.", e);
        }
    }

    /** Belt-and-braces: the stored blueprint file must still match the parsed anchors. */
    private static void validateAnchors(List<DocBlueprintSection> sections, List<IBodyElement> elements) {
        for (DocBlueprintSection section : sections) {
            int anchor = section.getAnchorParagraphIndex();
            if (anchor < 0) {
                continue; // implicit whole-document section
            }
            if (anchor >= elements.size() || !(elements.get(anchor) instanceof XWPFParagraph paragraph)) {
                throw new IllegalStateException("Blueprint file changed on disk; re-upload it.");
            }
            String anchorText = section.getAnchorText();
            String actual = paragraph.getText() == null ? "" : paragraph.getText().strip();
            if (anchorText != null && !actual.startsWith(anchorText)) {
                throw new IllegalStateException("Blueprint file changed on disk; re-upload it.");
            }
        }
    }

    /**
     * A fresh paragraph immediately before {@code insertBefore}, or appended at
     * the body end when there is no following section. A NEW cursor per insert:
     * an insert consumes the cursor's position, and since every insert lands
     * just before the unmoved next heading, natural block order comes out as
     * document order.
     */
    private static XWPFParagraph newParagraph(XWPFDocument doc, XWPFParagraph insertBefore) {
        if (insertBefore == null) {
            // Appends inside the final section's page geometry: the trailing
            // sectPr is not a body element, so headers/footers stay intact.
            return doc.createParagraph();
        }
        try (XmlCursor cursor = insertBefore.getCTP().newCursor()) {
            XWPFParagraph paragraph = doc.insertNewParagraph(cursor);
            if (paragraph == null) {
                throw new IllegalStateException("Could not insert a paragraph into the blueprint document.");
            }
            return paragraph;
        }
    }

    /** Plain runs with line breaks; no style, so the document's Normal look applies. */
    private static void writeParagraph(XWPFParagraph paragraph, String text) {
        XWPFRun run = paragraph.createRun();
        String[] lines = (text == null ? "" : text).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                run.addBreak();
            }
            run.setText(lines[i].stripTrailing());
        }
    }

    private static void writeImage(XWPFParagraph paragraph, DocImageAsset asset, byte[] bytes, int usableWidthEmu) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read image " + asset.getOriginalFileName() + ".", e);
        }
        if (image == null) {
            throw new IllegalStateException("Image " + asset.getOriginalFileName() + " is not a readable image.");
        }
        long widthEmu = (long) image.getWidth() * Units.EMU_PER_PIXEL;
        long heightEmu = (long) image.getHeight() * Units.EMU_PER_PIXEL;
        if (widthEmu > usableWidthEmu) { // shrink to fit; never upscale
            heightEmu = heightEmu * usableWidthEmu / widthEmu;
            widthEmu = usableWidthEmu;
        }
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        try {
            run.addPicture(new ByteArrayInputStream(bytes), pictureType(asset.getOriginalFileName()),
                    asset.getOriginalFileName(), (int) widthEmu, (int) heightEmu);
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new IllegalArgumentException("Unsupported image format: " + asset.getOriginalFileName(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not embed image " + asset.getOriginalFileName() + ".", e);
        }
    }

    private static void writeCaption(XWPFParagraph paragraph, String caption) {
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setItalic(true);
        run.setText(caption);
    }

    static int pictureType(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return Document.PICTURE_TYPE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return Document.PICTURE_TYPE_JPEG;
        }
        if (name.endsWith(".gif")) {
            return Document.PICTURE_TYPE_GIF;
        }
        if (name.endsWith(".bmp")) {
            return Document.PICTURE_TYPE_BMP;
        }
        throw new IllegalArgumentException("Unsupported image type: " + fileName);
    }

    /** Page width minus margins in EMUs, from the document's section properties; capped at 6.5in. */
    private static int usableWidthEmu(XWPFDocument doc) {
        double usableInches = MAX_IMAGE_WIDTH_INCHES;
        CTSectPr sectPr = doc.getDocument().getBody().isSetSectPr()
                ? doc.getDocument().getBody().getSectPr() : null;
        if (sectPr != null && sectPr.isSetPgSz()) {
            long pageTwips = twips(sectPr.getPgSz().getW());
            long marginTwips = 0;
            if (sectPr.isSetPgMar()) {
                CTPageMar margins = sectPr.getPgMar();
                marginTwips = twips(margins.getLeft()) + twips(margins.getRight());
            }
            if (pageTwips > marginTwips && pageTwips > 0) {
                usableInches = Math.min(usableInches, (pageTwips - marginTwips) / (double) TWIPS_PER_INCH);
            }
        }
        return (int) Math.round(usableInches * Units.EMU_PER_POINT * 72);
    }

    /** POI 5.x exposes twip measures as Object (schema union type). */
    private static long twips(Object measure) {
        if (measure == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(measure));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
