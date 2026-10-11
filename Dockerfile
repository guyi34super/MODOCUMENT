# ---- RDS CA bundle stage (DB TLS, C4 / SEC-028) -----------------------------
# The ECS task connects with sslmode=verify-full, which makes pgjdbc verify
# the server certificate chain AND host name — so the image must carry the
# CA that signs Amazon RDS server certificates. AWS's global bundle holds
# the per-region root CAs of every current RDS CA family (rds-ca-rsa2048-g1
# — the default for new instances — rds-ca-rsa4096-g1, rds-ca-ecc384-g1;
# 111 certificates when checked on 2026-10-09, af-south-1 included);
# pgjdbc reads it via sslrootcert=/app/certs/rds-global-bundle.pem
# (infra/terraform/envs/*). Same base image as the build stage (no extra
# pull); its own stage so the Maven layer cache never depends on it.
# Refresh = rebuild with --no-cache/--pull (AWS adds CAs; see
# docs/security/key-certificate-register.md). The build FAILS unless the
# download is a PEM bundle of parseable certificates — nothing else (no
# HTML error page, no private key block) — with at least one Amazon RDS CA.
FROM maven:3.9-eclipse-temurin-21 AS rds-ca
ARG RDS_CA_BUNDLE_URL=https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
RUN set -eu; \
    if ! command -v curl >/dev/null 2>&1 || ! command -v openssl >/dev/null 2>&1; then \
        apt-get update \
        && apt-get install -y --no-install-recommends curl openssl ca-certificates \
        && rm -rf /var/lib/apt/lists/*; \
    fi; \
    mkdir -p /out; \
    curl -fsSL --proto '=https' --tlsv1.2 --retry 3 \
        -o /out/rds-global-bundle.pem "$RDS_CA_BUNDLE_URL"; \
    blocks=$(grep -c -- '-----BEGIN ' /out/rds-global-bundle.pem || true); \
    certs=$(grep -c -- '-----BEGIN CERTIFICATE-----' /out/rds-global-bundle.pem || true); \
    parsed=$(openssl crl2pkcs7 -nocrl -certfile /out/rds-global-bundle.pem \
        | openssl pkcs7 -print_certs -noout | grep -c '^subject=' || true); \
    rds=$(openssl crl2pkcs7 -nocrl -certfile /out/rds-global-bundle.pem \
        | openssl pkcs7 -print_certs -noout | grep '^subject=' | grep -c 'Amazon RDS' || true); \
    if [ "$certs" -lt 1 ] || [ "$blocks" != "$certs" ] || [ "$parsed" != "$certs" ] || [ "$rds" -lt 1 ]; then \
        echo "RDS CA bundle from $RDS_CA_BUNDLE_URL rejected: PEM blocks=$blocks certificates=$certs parsed=$parsed Amazon-RDS=$rds" >&2; \
        exit 1; \
    fi; \
    echo "RDS CA bundle OK: $parsed certificates ($rds Amazon RDS CAs)"

# ---- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so source changes don't invalidate this layer.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# CHANGELOG.md → classpath:release/CHANGELOG.md (pom.xml copy-resources) for
# GET /api/system/release-notes; .dockerignore re-includes it (!CHANGELOG.md).
COPY CHANGELOG.md ./

# Release identity (ws24), declared after the dependency layer so a new
# commit never invalidates the go-offline cache. All three are optional
# (a plain `docker build .` still works, the version endpoint then reports
# commit null):
#   MODOCUMENT_VERSION  X.Y.Z or X.Y.Z-rc.N — must name pom.xml's version
#                       (the build fails otherwise); OCI label only, the app
#                       reports the pom version (BuildProperties).
#   GIT_COMMIT          full or short lowercase hex commit id. .git is not in
#                       the build context, so git-commit-id-maven-plugin is
#                       skipped and this writes the fallback git.properties.
#   BUILD_TIME          ISO-8601 UTC (2026-10-10T12:00:00Z): becomes Maven's
#                       project.build.outputTimestamp → BuildProperties time
#                       (+ reproducible jar entry timestamps).
# release.yml (Wave 2) passes all three; the same values become the OCI
# labels of the runtime stage below.
ARG MODOCUMENT_VERSION=
ARG GIT_COMMIT=
ARG BUILD_TIME=
# Tests run in CI (mvn -B verify); the image build just packages.
RUN set -eu; \
    if [ -n "$GIT_COMMIT" ]; then \
        if ! printf '%s' "$GIT_COMMIT" | grep -Eq '^[0-9a-f]{7,40}$'; then \
            echo "GIT_COMMIT must be a 7-40 character lowercase hex commit id" >&2; exit 1; \
        fi; \
        printf 'git.commit.id=%s\ngit.commit.id.abbrev=%s\n' \
            "$GIT_COMMIT" "$(printf '%s' "$GIT_COMMIT" | cut -c1-7)" \
            > src/main/resources/git.properties; \
    fi; \
    if [ -n "$BUILD_TIME" ] \
        && ! printf '%s' "$BUILD_TIME" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'; then \
        echo "BUILD_TIME must be ISO-8601 UTC, e.g. 2026-10-10T12:00:00Z" >&2; exit 1; \
    fi; \
    if [ -n "$MODOCUMENT_VERSION" ] \
        && ! printf '%s' "$MODOCUMENT_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-rc\.[1-9][0-9]*)?$'; then \
        echo "MODOCUMENT_VERSION must be X.Y.Z or X.Y.Z-rc.N" >&2; exit 1; \
    fi; \
    mvn -B -q -DskipTests -Dmaven.gitcommitid.skip=true \
        ${BUILD_TIME:+-Dproject.build.outputTimestamp=$BUILD_TIME} package; \
    built="$(sed -n 's/^build\.version=//p' target/classes/META-INF/build-info.properties)"; \
    if [ -n "$MODOCUMENT_VERSION" ] && [ "${MODOCUMENT_VERSION%-rc.*}" != "$built" ]; then \
        echo "MODOCUMENT_VERSION=$MODOCUMENT_VERSION does not match pom.xml version $built" >&2; exit 1; \
    fi; \
    test -f target/classes/release/CHANGELOG.md

# ---- runtime stage ----------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

# curl is only needed for the container healthcheck. LibreOffice Writer powers
# the document-creation PDF export (headless soffice); fonts-liberation keeps
# Arial/Times-based templates metric-compatible. Without them the app still
# runs — PDF download just answers 409 and .docx keeps working.
#
# NON-ROOT (SUP-S04): the app runs as `modocument` with a FIXED numeric
# uid/gid 10001 — numeric so orchestrators can verify it (Kubernetes
# runAsNonRoot refuses a named USER it cannot resolve), fixed so volume
# ownership stays stable across rebuilds. Its home (/home/modocument) is the
# only writable place besides /tmp and the storage root; soffice gets a
# per-conversion profile under /tmp (LibreOfficePdfConverter).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libreoffice-writer fonts-liberation \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 modocument \
    && useradd --uid 10001 --gid 10001 --create-home \
         --home-dir /home/modocument --shell /usr/sbin/nologin modocument

# Uploads and exports land under wwwroot/ relative to the working directory
# (override with MODOCUMENT_STORAGE_ROOT; mount a volume there). Ownership is
# fixed at BUILD time: Docker seeds a NEW, empty named volume mounted at
# /app/wwwroot from this directory — ownership included — so the container
# never needs root (no runtime chown, no privilege drop). Bind mounts and
# volumes created by older images (whose entrypoint chowned as root) keep
# their ownership: chown them to 10001:10001 once — see
# docs/security/web-hardening.md. /app itself and app.jar stay root-owned, so
# the app user cannot replace its own code.
RUN mkdir -p wwwroot/uploads wwwroot/exports \
    && chown -R 10001:10001 wwwroot \
    && chmod 0750 wwwroot

COPY --from=build /build/target/modocument-*.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
# AWS RDS CA bundle (rds-ca stage): root-owned, world-readable, read-only —
# the app user can use it for sslrootcert but never swap in its own CA.
# Unused unless the JDBC URL names it (compose's TLS-less PostgreSQL does not).
COPY --from=rds-ca /out/rds-global-bundle.pem /app/certs/rds-global-bundle.pem
RUN chmod 0755 /docker-entrypoint.sh \
    && chmod 0755 /app/certs \
    && chmod 0444 /app/certs/rds-global-bundle.pem

# From here on nothing runs as root: the entrypoint, the JVM, soffice
# children and the HEALTHCHECK curl all run as uid 10001.
USER 10001:10001

# Configuration comes from the environment, e.g.:
#   MODOCUMENT_JWT_SECRET=...           (REQUIRED in production: `openssl rand -base64 48`)
#   SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/modocument
#   SPRING_DATASOURCE_USERNAME=... / SPRING_DATASOURCE_PASSWORD=...
#   <SECRET>_FILE=/run/secrets/...      (file-based alternative for the JWT/MFA
#                                       keys and DB passwords; see
#                                       SecretFileEnvironmentPostProcessor)
#   MODOCUMENT_CORS_ALLOWED_ORIGINS=https://your-frontend.example
#   SPRING_PROFILES_ACTIVE=local        (dev conveniences; still needs PostgreSQL)
#   MODOCUMENT_AI_BASE_URL=... / _API_KEY / _MODEL  (EXTERNAL AI endpoint opt-in;
#                                       the default is a local Ollama at localhost:11434)
#   DOCLING_BASEURL=http://docling:5001 (rich pdf/doc/image OCR conversion)
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# OCI image labels (ws24) from the same build args as the build stage
# (validated there; empty when not passed). Declared last so a new commit
# only rewrites this metadata, never the apt/user layers above.
ARG MODOCUMENT_VERSION=
ARG GIT_COMMIT=
ARG BUILD_TIME=
LABEL org.opencontainers.image.title="MoDocument" \
      org.opencontainers.image.description="MoDocument document processing service (API)" \
      org.opencontainers.image.source="https://github.com/guyi34super/MODOCUMENT" \
      org.opencontainers.image.version="${MODOCUMENT_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.created="${BUILD_TIME}"

ENTRYPOINT ["/docker-entrypoint.sh"]
