# syntax=docker/dockerfile:1

# =============================================================================
# Evalora backend image
#
# IMPORTANT: build from the REPOSITORY ROOT, not from backend/.
# backend/pom.xml packages ../database/migrations into the jar, so the build
# context must contain both backend/ and database/ as siblings.
#
#   docker build -f backend/Dockerfile -t evalora-backend .
#
# Never pass the root .env into this build; credentials are provided at run
# time by Docker Compose / the platform environment.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1 - build the executable jar
# -----------------------------------------------------------------------------
# The Maven Wrapper in backend/ downloads Maven 3.10.0 itself, so the base
# image only needs a JDK 21 (no separate Maven image is required).
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

# Keep the same relative layout as the repository: backend/ and database/ are
# siblings. This is what makes ${project.basedir}/../database/migrations work.
COPY backend/ ./backend/
COPY database/ ./database/

# Git tracks mvnw without the executable bit, so set it before running.
RUN chmod +x backend/mvnw \
    && cd backend \
    && ./mvnw -B -DskipTests package

# -----------------------------------------------------------------------------
# Stage 2 - minimal Java 21 runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# Run the application as an unprivileged system user.
RUN groupadd --system app \
    && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

# curl is used only by the container health check. Install it while still root,
# then continue as the unprivileged application user below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Copy ONLY the executable jar produced by the build stage.
COPY --from=build --chown=app:app \
    /workspace/backend/target/backend-0.0.1-SNAPSHOT.jar /app/app.jar

# Safe, non-secret defaults. They can still be overridden at run time.
# SPRING_PROFILES_ACTIVE=prod keeps the dev-only bootstrap account and the
# dev password-reset endpoint disabled.
ENV SPRING_PROFILES_ACTIVE=prod \
    MIGRATIONS_LOCATION=classpath:db/migration \
    PORT=8080

USER app

EXPOSE 8080

# Unauthenticated health probe. SecurityConfig permits /actuator/health and
# application.properties exposes no other actuator endpoint. ${PORT} is the
# same variable the application binds to (server.port=${PORT:8080}).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/actuator/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
