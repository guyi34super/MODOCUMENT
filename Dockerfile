# ---- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so source changes don't invalidate this layer.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI (mvn -B verify); the image build just packages.
RUN mvn -B -q -DskipTests package

# ---- runtime stage ----------------------------------------------------------
FROM eclipse-temurin:21-jre
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
RUN chmod 0755 /docker-entrypoint.sh

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

ENTRYPOINT ["/docker-entrypoint.sh"]
