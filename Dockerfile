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

# curl is only needed for the container healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --home-dir /app --shell /usr/sbin/nologin modocument

# Uploads and exports land under wwwroot/ relative to the working directory
# (override with MODOCUMENT_STORAGE_ROOT; mount a volume there).
RUN mkdir -p wwwroot/uploads wwwroot/exports && chown -R modocument:modocument /app

COPY --from=build /build/target/modocument-*.jar app.jar

USER modocument

# Configuration comes from the environment, e.g.:
#   MODOCUMENT_JWT_SECRET=...           (REQUIRED in production: `openssl rand -base64 48`)
#   SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/modocument
#   SPRING_DATASOURCE_USERNAME=... / SPRING_DATASOURCE_PASSWORD=...
#   MODOCUMENT_CORS_ALLOWED_ORIGINS=https://your-frontend.example
#   SPRING_PROFILES_ACTIVE=local        (in-memory H2 dev mode, zero dependencies)
#   AIFILES_OPENAI_APIKEY=...           (enables the LLM endpoints)
#   DOCLING_BASEURL=http://docling:5001 (rich pdf/doc/image OCR conversion)
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
