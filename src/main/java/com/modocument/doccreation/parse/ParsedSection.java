package com.modocument.doccreation.parse;

import java.util.List;

/**
 * One section found in a blueprint document. {@code anchorParagraphIndex} is
 * the index of the section's heading paragraph among the document's body
 * elements (paragraphs AND tables — the same index space generation uses);
 * -1 with headingLevel 0 marks the implicit section of a heading-less file.
 */
public record ParsedSection(int orderIndex,
                            String title,
                            int headingLevel,
                            int anchorParagraphIndex,
                            String anchorText,
                            List<String> keywords) {
}
