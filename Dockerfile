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

# Uploads and exports land under wwwroot/ relative to the working directory.
RUN mkdir -p wwwroot/uploads wwwroot/exports

COPY --from=build /build/target/modocument-*.jar app.jar

# Configuration comes from the environment, e.g.:
#   SPRING_PROFILES_ACTIVE=local        (in-memory H2, seeded — zero dependencies)
#   AIFILES_OPENAI_APIKEY=...           (enables the LLM endpoints)
#   DOCLING_BASEURL=http://docling:5001 (rich pdf/doc/image OCR conversion)
#   SPRING_DATASOURCE_URL=jdbc:sqlserver://...   (production database)
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
