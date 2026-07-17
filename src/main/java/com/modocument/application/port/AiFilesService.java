package com.modocument.application.port;

/** Sends prompts to the LLM (OpenAI chat completion) and returns the raw reply. */
public interface AiFilesService {

    String sendPrompt(String prompt);
}
