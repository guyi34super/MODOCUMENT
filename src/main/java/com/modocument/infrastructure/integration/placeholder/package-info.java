/**
 * Wave-3 placeholders (Supervisor). Each bean here stands in for a port whose
 * real implementation is owned by another engineer, so every branch boots on
 * its own. <b>The owner deletes the placeholder in the same commit that adds
 * the real {@code @Component}</b> (two beans of one port fail the context):
 * <ul>
 *   <li>{@code PlaceholderSecretVault}, {@code PlaceholderEgressGuard} — SE1</li>
 *   <li>{@code PlaceholderCurrentClientProvider}, {@code PlaceholderApiClientIssuer} — SE2</li>
 *   <li>{@code PlaceholderRecordsIngestion}, {@code PlaceholderIntegrationEventLog},
 *       {@code PlaceholderAgentRunPort} — SE4</li>
 * </ul>
 */
package com.modocument.infrastructure.integration.placeholder;
