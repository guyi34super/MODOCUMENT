package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses the font to draw a value with: the template's own font when it is embedded (or a standard 14
 * font) and has every glyph needed, else the bundled Liberation font closest to it (serif / sans / mono,
 * bold, italic). One instance per output document.
 */
final class FontResolver {

    static final List<String> BUNDLED = List.of(
            "LiberationSans", "LiberationSans-Bold", "LiberationSans-Italic", "LiberationSans-BoldItalic",
            "LiberationSerif", "LiberationSerif-Bold", "LiberationSerif-Italic", "LiberationSerif-BoldItalic",
            "LiberationMono", "LiberationMono-Bold", "LiberationMono-Italic", "LiberationMono-BoldItalic");

    private static final Map<String, byte[]> FONT_BYTES = new ConcurrentHashMap<>();

    private final PDDocument doc;
    private final Map<String, PDFont> loaded = new HashMap<>();
    private Map<String, PDFont> documentFonts;

    FontResolver(PDDocument doc) {
        this.doc = doc;
    }

    static boolean isBundledName(String name) {
        return name != null && BUNDLED.contains(name);
    }

    /** The bundled family + style closest to a PDF font name (subset prefix ignored). */
    static String bundledNameFor(String fontName) {
        if (isBundledName(fontName)) {
            return fontName;
        }
        String n = fontName == null ? "" : fontName.toLowerCase(Locale.ROOT);
        int plus = n.indexOf('+');
        if (plus == 6) {
            n = n.substring(plus + 1);
        }
        String family = "LiberationSans";
        if (n.contains("courier") || n.contains("mono") || n.contains("consol") || n.contains("typewriter")) {
            family = "LiberationMono";
        } else if (n.contains("times") || n.contains("serif") && !n.contains("sans") || n.contains("roman")
                || n.contains("georgia") || n.contains("garamond") || n.contains("book") || n.contains("cambria")
                || n.contains("minion") || n.contains("palatino")) {
            family = "LiberationSerif";
        }
        boolean bold = n.contains("bold") || n.contains("black") || n.contains("heavy") || n.contains("semibold")
                || n.contains("demi");
        boolean italic = n.contains("italic") || n.contains("oblique");
        return styled(family, bold, italic);
    }

    static String styled(String family, boolean bold, boolean italic) {
        if (bold && italic) {
            return family + "-BoldItalic";
        }
        if (bold) {
            return family + "-Bold";
        }
        if (italic) {
            return family + "-Italic";
        }
        return family;
    }

    static byte[] bundledBytes(String name) {
        String file = name.contains("-") ? name : name + "-Regular";
        return FONT_BYTES.computeIfAbsent(file, f -> {
            try (InputStream in = FontResolver.class.getResourceAsStream("/fonts/" + f + ".ttf")) {
                if (in == null) {
                    throw new IllegalStateException("Bundled font missing: " + f);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** A bundled font, embedded as a subset in this document. */
    PDFont bundled(String name) {
        String key = isBundledName(name) ? name : bundledNameFor(name);
        return loaded.computeIfAbsent(key, k -> {
            try {
                return PDType0Font.load(doc, new ByteArrayInputStream(bundledBytes(k)), true);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The font to draw {@code text} with for a region whose font is {@code fontName}. */
    PDFont choose(String fontName, String text) {
        if (fontName != null && !fontName.isBlank() && !isBundledName(fontName)) {
            PDFont own = findDocumentFont(fontName);
            if (own != null && usable(own) && covers(own, text)) {
                return own;
            }
        }
        PDFont bundled = bundled(fontName == null ? "LiberationSans" : fontName);
        return bundled;
    }

    private static boolean usable(PDFont font) {
        if (font instanceof PDType3Font) {
            return false;
        }
        if (font instanceof PDType1Font t1 && t1.isStandard14()) {
            return true;
        }
        return font.isEmbedded();
    }

    /** Every character can be encoded and has a real glyph. */
    static boolean covers(PDFont font, String text) {
        if (text == null) {
            return true;
        }
        int[] cps = text.codePoints().toArray();
        for (int cp : cps) {
            if (Character.isWhitespace(cp)) {
                continue;
            }
            String s = new String(Character.toChars(cp));
            try {
                byte[] bytes = font.encode(s);
                if (bytes == null || bytes.length == 0) {
                    return false;
                }
                int code = font.readCode(new ByteArrayInputStream(bytes));
                if (font instanceof PDVectorFont vf && !vf.hasGlyph(code)) {
                    return false;
                }
                if (font.getWidth(code) <= 0) {
                    return false;
                }
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    /** A font of this document by its PDF name (first page that has it wins). */
    PDFont findDocumentFont(String name) {
        if (documentFonts == null) {
            documentFonts = new HashMap<>();
            Set<Object> seen = new HashSet<>();
            for (PDPage page : doc.getPages()) {
                collect(page.getResources(), seen, 0);
            }
        }
        return documentFonts.get(name);
    }

    private void collect(PDResources res, Set<Object> seen, int depth) {
        if (res == null || depth > 6 || !seen.add(res.getCOSObject())) {
            return;
        }
        for (COSName fn : res.getFontNames()) {
            try {
                PDFont f = res.getFont(fn);
                if (f != null && f.getName() != null) {
                    documentFonts.putIfAbsent(f.getName(), f);
                }
            } catch (IOException | RuntimeException ignored) {
                // unreadable font: not a candidate
            }
        }
        for (COSName xn : res.getXObjectNames()) {
            try {
                PDXObject x = res.getXObject(xn);
                if (x instanceof PDFormXObject form) {
                    collect(form.getResources(), seen, depth + 1);
                }
            } catch (IOException | RuntimeException ignored) {
                // skip
            }
        }
    }

    /** Ascent and descent (descent negative), per unit font size. */
    static float[] metrics(PDFont font) {
        ContentWalker.FontInfo fi = ContentWalker.FontInfo.of(font);
        return new float[] {fi.ascent, fi.descent};
    }

    static float width(PDFont font, String text, float size) {
        try {
            return font.getStringWidth(text) / 1000f * size;
        } catch (IOException | RuntimeException e) {
            return text.length() * size * 0.6f;
        }
    }
}
