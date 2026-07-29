package com.modocument.application.usecase;

import com.modocument.application.dto.DataCleaningPromptInput;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.privacy.PiiTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Optional LLM cleaning pass over extracted rows, batch by batch. Degrades
 * gracefully: with no API key (or an LLM failure) the original rows are kept
 * — deterministic normalization already happened during extraction.
 */
@Service
public class CleanRecordsUseCase {

    /** Same per-call row cap as chat editing. */
    static final int MAX_ROWS_PER_CALL = 500;

    private static final Logger log = LoggerFactory.getLogger(CleanRecordsUseCase.class);

    private final List<PromptStrategy> promptStrategies;
    private final AiFilesService aiFilesService;
    private final PiiTokenizer tokenizer;

    public CleanRecordsUseCase(List<PromptStrategy> promptStrategies,
                               AiFilesService aiFilesService,
                               PiiTokenizer tokenizer) {
        this.promptStrategies = promptStrategies;
        this.aiFilesService = aiFilesService;
        this.tokenizer = tokenizer;
    }

    /**
     * @return cleaned rows, or the original rows when the LLM is not
     *         configured or a batch fails.
     */
    public List<Map<String, String>> clean(List<String> columns,
                                           List<Map<String, String>> rows, String dataSpec) {
        PromptStrategy strategy = promptStrategies.stream()
                .filter(s -> s.getType().equals("DataCleaning"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("DataCleaning prompt not registered."));

        // One session for the whole run: tokens stay stable across batches.
        // Sensitive cells travel as placeholders — the model cannot reformat
        // them (it echoes the token back), which is the intended trade-off.
        PiiTokenizer.Session session = tokenizer.newSession();
        List<Map<String, String>> cleaned = new ArrayList<>(rows.size());
        for (int from = 0; from < rows.size(); from += MAX_ROWS_PER_CALL) {
            List<Map<String, String>> batch =
                    rows.subList(from, Math.min(rows.size(), from + MAX_ROWS_PER_CALL));
            try {
                String prompt = strategy.buildPrompt(new DataCleaningPromptInput(
                        IngestionJobUseCase.toJson(
                                new TableData(columns, session.tokenizeRows(batch))),
                        dataSpec));
                TableData result = aiFilesService.sendForObject(prompt,
                        new ParameterizedTypeReference<TableData>() {
                        });
                if (result != null && result.getRows() != null
                        && result.getRows().size() == batch.size()) {
                    cleaned.addAll(session.detokenizeRows(result.getRows()));
                    continue;
                }
                log.warn("LLM cleaning returned a mismatched batch; keeping original rows");
            } catch (Exception e) {
                log.info("LLM cleaning skipped ({}); keeping original rows", e.getMessage());
            }
            cleaned.addAll(batch);
        }
        return cleaned;
    }
}
