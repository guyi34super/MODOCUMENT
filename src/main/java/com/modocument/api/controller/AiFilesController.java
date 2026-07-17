package com.modocument.api.controller;

import com.modocument.application.port.AiFilesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AiFilesController {

    private final AiFilesService aiFilesService;

    public AiFilesController(AiFilesService aiFilesService) {
        this.aiFilesService = aiFilesService;
    }

    public record PromptRequest(String prompt) {
    }

    /**
     * POST /api/aifiles/send
     * Body (JSON): { "prompt": "Your prompt here..." }
     */
    @PostMapping("/api/aifiles/send")
    public ResponseEntity<?> sendPrompt(@RequestBody(required = false) PromptRequest body) {
        if (body == null || body.prompt() == null || body.prompt().isBlank()) {
            return ResponseEntity.badRequest().body("Prompt must not be empty.");
        }

        try {
            String aiResponse = aiFilesService.sendPrompt(body.prompt());
            return ResponseEntity.ok(Map.of("response", aiResponse));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error calling LLM: " + ex.getMessage());
        }
    }
}
