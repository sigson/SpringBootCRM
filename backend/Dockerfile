# ---------------------------------------------------------------------------
# SpringBootCRM backend — container image for Render (or any Docker host).
#
# Multi-stage: the jar is built inside the image, so the host needs nothing but
# Docker and the deploy does not depend on a jar someone remembered to build.
# Render's Docker runtime builds this from the repository on every push.
# ---------------------------------------------------------------------------

# ---- stage 1: build the jar -----------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# pom.xml first, on its own layer: dependency resolution is the slow part of the
# build and it only has to re-run when the pom actually changes. Editing Java
# sources then rebuilds in seconds instead of re-downloading the world.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# Tests are skipped here on purpose: the image build is a deploy step, not a CI
# gate. Run `mvn verify` in CI (see .github/workflows/ci.yml).
RUN mvn -B -ntp -DskipTests package \
    && cp target/springbootcrm-backend-*.jar /build/app.jar

# ---- stage 2: runtime ------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a non-root user. Nothing in the app needs root, and a container that
# never needs it should never have it.
RUN useradd --system --create-home --uid 10001 crm
COPY --from=build /build/app.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh && chown -R crm:crm /app
USER crm

# Render injects PORT and routes external traffic to it; application.yml reads
# it. 8080 is only the default for a plain `docker run`.
ENV PORT=8080
EXPOSE 8080

# Sized for Render's 512 MB free instance. MaxRAMPercentage (not -Xmx) so the
# same image behaves sensibly on a larger plan; SerialGC because the parallel
# collectors' extra threads and heap reservations are pure overhead on a single
# shared vCPU. Override the whole thing with JAVA_OPTS.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"

ENTRYPOINT ["/app/docker-entrypoint.sh"]
