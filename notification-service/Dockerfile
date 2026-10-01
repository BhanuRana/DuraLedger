# syntax=docker/dockerfile:1
#
# Packages a jar built by `./mvnw package` (locally or in CI): the image never compiles code.
# Why: the build generates jOOQ classes from a real Postgres through Testcontainers, which needs a
# Docker daemon, and Docker inside `docker build` is fragile. So the jar is built where Docker
# already is, with its tests, and this image only packages it.

# --- stage 1: split the fat jar into layers ---------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS layers
WORKDIR /build
COPY target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# --- stage 2: runtime ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
# Least-changing layer first: a code-only change rebuilds and pushes just the last few hundred KB.
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER app

# Size the heap from the container's memory limit, not the host's RAM: a fixed -Xmx that ignores the
# limit is the classic OOM-kill trap. 75% leaves room for metaspace, thread stacks and the direct
# buffers of the Pub/Sub client. Crash fast on OOM, so the orchestrator restarts a clean process.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
