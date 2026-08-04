package com.modocument.application.usecase;

import com.modocument.application.dto.SaveExtractionSourceRequest;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.ExtractionSourceStore;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.model.SourceType;
import com.modocument.domain.model.UrlNormalizer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * CRUD + compliance validation for extraction sources. A source cannot be
 * created without acknowledging its terms of use, website sources must
 * allow-list their domains (and may not carry credentials at all), and API
 * auth is an env-var reference — never a stored secret.
 */
@Service
public class ExtractionSourceUseCase {

    private final ExtractionSourceStore sources;
    private final CurrentUserProvider currentUser;

    public ExtractionSourceUseCase(ExtractionSourceStore sources, CurrentUserProvider currentUser) {
        this.sources = sources;
        this.currentUser = currentUser;
    }

    public ExtractionSource create(SaveExtractionSourceRequest request) {
        ExtractionSource source = new ExtractionSource();
        source.setOwnerUserId(currentUser.requireUserId());
        apply(source, request);
        return sources.save(source);
    }

    public ExtractionSource update(String id, SaveExtractionSourceRequest request) {
        // get(id) is owner-scoped, so updating another user's source is a 404.
        ExtractionSource source = get(id);
        apply(source, request);
        return sources.save(source);
    }

    public ExtractionSource get(String id) {
        return sources.getByIdAndOwner(id, currentUser.requireUserId()).orElseThrow(
                () -> new NoSuchElementException("Extraction source " + id + " not found."));
    }

    public List<ExtractionSource> list() {
        return sources.listByOwner(currentUser.requireUserId());
    }

    public void delete(String id) {
        get(id);
        sources.delete(id);
    }

    private void apply(ExtractionSource source, SaveExtractionSourceRequest request) {
        validate(request);
        source.setName(request.getName());
        source.setType(request.getType());
        source.setTermsAcknowledged(request.isTermsAcknowledged());
        source.setDataSpec(request.getDataSpec());
        source.setWeb(request.getWeb());
        source.setApi(request.getApi());
        source.setDb(request.getDb());
    }

    private void validate(SaveExtractionSourceRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("name is required.");
        }
        if (request.getType() == null) {
            throw new IllegalArgumentException("type is required (WEBSITE, REST_API, DATABASE).");
        }
        if (!request.isTermsAcknowledged()) {
            throw new IllegalArgumentException("termsAcknowledged must be true: confirm you are "
                    + "authorized to access this source under its terms of service and "
                    + "applicable data-protection law.");
        }
        switch (request.getType()) {
            case WEBSITE -> validateWeb(request);
            case REST_API -> validateApi(request);
            case DATABASE -> validateDb(request);
        }
    }

    private void validateWeb(SaveExtractionSourceRequest request) {
        if (request.getWeb() == null || request.getWeb().getSeedUrls().isEmpty()) {
            throw new IllegalArgumentException("web.seedUrls is required for WEBSITE sources.");
        }
        if (request.getWeb().getAllowedDomains().isEmpty()) {
            throw new IllegalArgumentException("web.allowedDomains is required: the crawl never "
                    + "leaves these domains.");
        }
        for (String seed : request.getWeb().getSeedUrls()) {
            String normalized = UrlNormalizer.normalize(seed);
            if (normalized == null) {
                throw new IllegalArgumentException(
                        "Seed URL '" + seed + "' is not a valid http(s) URL.");
            }
            String host = UrlNormalizer.hostOf(normalized);
            boolean covered = request.getWeb().getAllowedDomains().stream()
                    .anyMatch(d -> host.equals(d.toLowerCase().trim())
                            || host.endsWith("." + d.toLowerCase().trim()));
            if (!covered) {
                throw new IllegalArgumentException("Seed URL host '" + host
                        + "' is not covered by web.allowedDomains.");
            }
        }
    }

    private void validateApi(SaveExtractionSourceRequest request) {
        if (request.getApi() == null || request.getApi().getBaseUrl() == null
                || request.getApi().getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("api.baseUrl is required for REST_API sources.");
        }
    }

    private void validateDb(SaveExtractionSourceRequest request) {
        if (request.getDb() == null || request.getDb().getJdbcUrl() == null
                || request.getDb().getJdbcUrl().isBlank()
                || request.getDb().getTable() == null || request.getDb().getTable().isBlank()) {
            throw new IllegalArgumentException(
                    "db.jdbcUrl and db.table are required for DATABASE sources "
                            + "(credentials are supplied per-run, never stored).");
        }
    }
}
