package com.modocument.api.controller;

import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.port.AiFilesService;
import com.modocument.domain.model.Permission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Raw prompt passthrough, kept for diagnostics. It is a paid provider call
 * behind an authenticated endpoint, so the prompt is length-bounded and
 * provider failures are left to {@link AiExceptionHandler} rather than being
 * echoed back verbatim.
 */
@RestController
@RequiresPermission(Permission.AI_USE)
public class AiFilesController {

    /** Enough for a diagnostic prompt, far short of a way to burn credits. */
    static final int MAX_PROMPT_LENGTH = 8000;

    private final AiFilesService aiFilesService;

    public AiFilesController(AiFilesService aiFilesService) {
        this.aiFilesService = aiFilesService;
    }

    public record PromptRequest(@Size(max = MAX_PROMPT_LENGTH) String prompt) {
    }

    /**
     * POST /api/aifiles/send
     * Body (JSON): { "prompt": "Your prompt here..." }
     */
    @PostMapping("/api/aifiles/send")
    public ResponseEntity<?> sendPrompt(@Valid @RequestBody(required = false) PromptRequest body) {
        if (body == null || body.prompt() == null || body.prompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Prompt must not be empty."));
        }
        if (body.prompt().length() > MAX_PROMPT_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Prompt is longer than " + MAX_PROMPT_LENGTH + " characters."));
        }
        return ResponseEntity.ok(Map.of("response", aiFilesService.sendPrompt(body.prompt())));
    }
}
