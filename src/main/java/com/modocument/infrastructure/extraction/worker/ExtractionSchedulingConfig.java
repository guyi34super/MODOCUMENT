package com.modocument.infrastructure.extraction.worker;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the scheduled tasks the extraction feature relies on (retry sweep). */
@Configuration
@EnableScheduling
public class ExtractionSchedulingConfig {
}
