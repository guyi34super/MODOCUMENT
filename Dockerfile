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
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libreoffice-writer fonts-liberation \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --home-dir /app --shell /usr/sbin/nologin modocument

# Uploads and exports land under wwwroot/ relative to the working directory
# (override with MODOCUMENT_STORAGE_ROOT; mount a volume there). Ownership is
# fixed at BUILD time (SUP-S04): a fresh named volume mounted at /app/wwwroot
# is seeded by Docker from the image's directory, modocument ownership
# included, so the container never needs root at runtime. (Bind mounts keep
# host ownership — chown them on the host once if you use one; see README.)
RUN mkdir -p wwwroot/uploads wwwroot/exports && chown -R modocument:modocument /app

COPY --from=build /build/target/modocument-*.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

# Non-root from the first instruction of runtime (SUP-S04): no root window,
# no runtime chown/privilege drop. The healthcheck curl below also runs as
# this user.
USER modocument

# Configuration comes from the environment, e.g.:
#   MODOCUMENT_JWT_SECRET=...           (REQUIRED in production: `openssl rand -base64 48`)
#   SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/modocument
#   SPRING_DATASOURCE_USERNAME=... / SPRING_DATASOURCE_PASSWORD=...
#   MODOCUMENT_CORS_ALLOWED_ORIGINS=https://your-frontend.example
#   SPRING_PROFILES_ACTIVE=local        (dev conveniences; still needs PostgreSQL)
#   MODOCUMENT_AI_BASE_URL=... / _API_KEY / _MODEL  (EXTERNAL AI endpoint opt-in;
#                                       the default is a local Ollama at localhost:11434)
#   DOCLING_BASEURL=http://docling:5001 (rich pdf/doc/image OCR conversion)
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["/docker-entrypoint.sh"]
