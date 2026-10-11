# MoDocument Connector Agent image (ws26, agent/README.md).
#
# Build context: agent/, plus connector-core as a named context (release.yml:
# `docker buildx build --build-context connector-core=connector-core … agent`).
# Runtime: distroless Java 21, NON-ROOT (uid/gid 65532), no shell, no
# package manager, NO EXPOSE — the agent has no inbound port. It writes only
# to its state volume (/var/lib/modocument-agent) and /tmp, so it runs with
# `--read-only --tmpfs /tmp`.

# ---- connector-core sources ---------------------------------------------------
# The agent's connectors are connector-core (docs/integration/
# connector-sharing.md), a sibling of agent/ and so outside this build
# context. It comes in as a NAMED build context, which replaces this stage:
#   docker buildx build --build-context connector-core=connector-core agent
# (release.yml does). Without it the build stops here with that hint.
FROM maven:3.9-eclipse-temurin-21 AS connector-core
RUN echo "agent/Dockerfile needs the connector-core sources: docker buildx build" \
         "--build-context connector-core=connector-core agent" >&2 \
    && exit 1

# ---- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY --from=connector-core pom.xml /core/pom.xml
COPY --from=connector-core src /core/src
COPY pom.xml .
# Maven repository as a BuildKit cache mount (ws29; same id as the API
# image, so both builds share one warm repository on a builder). connector-
# core is installed first: the agent depends on it as a normal artifact.
RUN --mount=type=cache,target=/root/.m2,id=modocument-m2,sharing=locked \
    mvn -B -q -f /core/pom.xml -DskipTests install \
    && mvn -B -q dependency:go-offline
COPY src ./src
# Release identity (same args as the API image; all optional):
#   MODOCUMENT_VERSION  X.Y.Z or X.Y.Z-rc.N — must name pom.xml's version
#   GIT_COMMIT          OCI label only
#   BUILD_TIME          ISO-8601 UTC → reproducible jar timestamps
ARG MODOCUMENT_VERSION=
ARG BUILD_TIME=
# Tests run in CI (mvn -B -f agent/pom.xml verify); the image build packages.
RUN --mount=type=cache,target=/root/.m2,id=modocument-m2,sharing=locked \
    set -eu; \
    if [ -n "$BUILD_TIME" ] \
        && ! printf '%s' "$BUILD_TIME" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'; then \
        echo "BUILD_TIME must be ISO-8601 UTC, e.g. 2026-10-10T12:00:00Z" >&2; exit 1; \
    fi; \
    mvn -B -q -f /core/pom.xml -DskipTests \
        ${BUILD_TIME:+-Dproject.build.outputTimestamp=$BUILD_TIME} install; \
    mvn -B -q -DskipTests ${BUILD_TIME:+-Dproject.build.outputTimestamp=$BUILD_TIME} package; \
    built="$(sed -n 's/^build\.version=//p' target/classes/META-INF/build-info.properties)"; \
    if [ -n "$MODOCUMENT_VERSION" ] && [ "${MODOCUMENT_VERSION%-rc.*}" != "$built" ]; then \
        echo "MODOCUMENT_VERSION=$MODOCUMENT_VERSION does not match agent/pom.xml version $built" >&2; exit 1; \
    fi; \
    cp "target/modocument-agent-$built.jar" /build/agent.jar; \
    mkdir -p /build/state

# ---- runtime stage ----------------------------------------------------------
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /build/agent.jar /app/agent.jar
# The state volume's mount point, owned by the runtime user: a NEW named
# volume is seeded with this ownership, so the agent never needs root.
COPY --from=build --chown=65532:65532 /build/state /var/lib/modocument-agent
USER 65532:65532

ENV MODOCUMENT_AGENT_STATE_DIR=/var/lib/modocument-agent \
    MODOCUMENT_AGENT_ALLOWLIST=/etc/modocument-agent/allowlist.yml \
    MODOCUMENT_SECRETS_DIR=/etc/modocument-agent/secrets

# Healthy while the last successful poll/heartbeat is < 2 minutes old
# (checked without starting Spring; no port, no shell needed).
HEALTHCHECK --interval=60s --timeout=20s --start-period=60s --retries=3 \
    CMD ["/usr/bin/java", "-XX:-UsePerfData", "-XX:TieredStopAtLevel=1", "-jar", "/app/agent.jar", "healthcheck"]

ARG MODOCUMENT_VERSION=
ARG GIT_COMMIT=
ARG BUILD_TIME=
LABEL org.opencontainers.image.title="MoDocument Connector Agent" \
      org.opencontainers.image.description="On-prem agent connecting bank systems to MoDocument (outbound HTTPS only)" \
      org.opencontainers.image.source="https://github.com/guyi34super/MODOCUMENT" \
      org.opencontainers.image.version="${MODOCUMENT_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.created="${BUILD_TIME}"

# -XX:-UsePerfData: no hsperfdata files (read-only root file system).
ENTRYPOINT ["/usr/bin/java", "-XX:-UsePerfData", "-XX:MaxRAMPercentage=75", "-jar", "/app/agent.jar"]
