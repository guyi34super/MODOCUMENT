package com.modocument.infrastructure.transform;

import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.mapping.MappingProperties;
import com.modocument.application.mapping.ValueShapeScorer;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.infrastructure.transform.code.CodeExtraction;
import com.modocument.infrastructure.transform.code.CodeTableExtractor;
import com.modocument.infrastructure.transform.code.ExtractionInput;
import com.modocument.infrastructure.transform.code.FieldSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the {@link CodeTableExtractor} for a template's document (ws23): builds
 * the {@link ExtractionInput} from the template's fields with their V230
 * hints, the global {@code mapping.aliases} and the template's validation
 * rules (for value-shape scoring), and applies the confidence gate. Shared by
 * the ingestion parse stage and the legacy transform.
 */
@Component
public class CodeParseSupport {

    private static final Logger log = LoggerFactory.getLogger(CodeParseSupport.class);

    /** What the code extractor made of a document. */
    public record Outcome(Optional<CodeExtraction> accepted, CodeExtraction best) {

        /** The best score reached, accepted or not; null when no strategy found rows. */
        public Double bestConfidence() {
            return best == null ? null : best.confidence();
        }

        /** "best confidence 0.52" or "no table found" — for failure messages. */
        public String describeBest() {
            return best == null ? "no table found"
                    : String.format(Locale.ROOT, "best confidence %.2f", best.confidence());
        }
    }

    private final CodeTableExtractor extractor;
    private final MappingProperties mappingProperties;
    private final ValueShapeScorer valueShapeScorer;

    public CodeParseSupport(CodeTableExtractor extractor, MappingProperties mappingProperties,
                            ValueShapeScorer valueShapeScorer) {
        this.extractor = extractor;
        this.mappingProperties = mappingProperties;
        this.valueShapeScorer = valueShapeScorer;
    }

    /** The template's fields as the extractor sees them (unnamed fields are skipped). */
    public static List<FieldSpec> fieldSpecs(List<TemplateFields> fields) {
        if (fields == null) {
            return List.of();
        }
        return fields.stream()
                .filter(field -> field.getFieldName() != null && !field.getFieldName().isBlank())
                .map(FieldSpec::from)
                .toList();
    }

    /**
     * @param templateId for the template's validation rules; 0 or less for none
     * @param text       the document text (may be empty when only the file is readable)
     * @param file       the uploaded file (native sheets, raw CSV bytes, PDF layout); may be null
     * @param hints      the parse stage's pre-scan; may be null
     */
    public ExtractionInput input(int templateId, List<FieldSpec> fields, String text, Path file,
                                 TableExtractionHints hints) {
        return ExtractionInput.builder()
                .text(text)
                .file(file)
                .fields(fields)
                .hints(hints)
                .globalAliases(mappingProperties == null ? Map.of() : mappingProperties.getAliases())
                .rulesByField(rulesByField(templateId))
                .build();
    }

    /** Runs every strategy and applies the confidence gate. */
    public Outcome extract(ExtractionInput input, double minConfidence, boolean codeOnly) {
        CodeExtraction best = extractor.extract(input).orElse(null);
        return new Outcome(CodeTableExtractor.accept(best, minConfidence, codeOnly), best);
    }

    private Map<String, List<ValidationRule>> rulesByField(int templateId) {
        if (templateId <= 0 || valueShapeScorer == null) {
            return Map.of();
        }
        try {
            return valueShapeScorer.rulesByField(templateId);
        } catch (RuntimeException e) {
            // Value-shape scoring then falls back to the data types alone.
            log.warn("Could not load validation rules of template {} for code extraction: {}",
                    templateId, e.getClass().getSimpleName());
            return Map.of();
        }
    }
}
