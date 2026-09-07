# syntax=docker/dockerfile:1.7

# ---- build -----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# POMs first, so the dependency layer is cached independently of source changes: a
# one-line Java edit then rebuilds in seconds rather than re-resolving everything.
COPY pom.xml .
COPY client/pom.xml client/
COPY server/pom.xml server/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline || true

COPY client/src client/src
COPY server/src server/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

# Explode the executable jar into Spring Boot's layers, so dependencies (which rarely
# change) land in a lower image layer than application classes (which always do). A
# redeploy then pushes kilobytes instead of the whole fat jar.
RUN java -Djarmode=tools -jar server/target/*-exec.jar extract --layers --launcher --destination /extracted

# ---- runtime ---------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble AS runtime

# Never root: this process holds credentials that can spend money.
RUN groupadd --system --gid 1001 mcp \
 && useradd --system --uid 1001 --gid mcp --home /app --shell /usr/sbin/nologin mcp
WORKDIR /app

COPY --from=build --chown=mcp:mcp /extracted/dependencies/ ./
COPY --from=build --chown=mcp:mcp /extracted/spring-boot-loader/ ./
COPY --from=build --chown=mcp:mcp /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=mcp:mcp /extracted/application/ ./

USER mcp
EXPOSE 8080

# Container-aware heap sizing: the JVM default of a quarter of host RAM is wrong inside a
# memory limit. ExitOnOutOfMemoryError so a wedged container is restarted rather than
# limping along unable to serve.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# start-period covers Liquibase applying the changelog against a cold database.
# Uses bash /dev/tcp rather than curl, which this base image does not ship — adding curl
# purely for a healthcheck would grow the attack surface of an image holding credentials.
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /.well-known/oauth-authorization-server HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q '200'"]

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

LABEL org.opencontainers.image.title="google-ads-mcp" \
      org.opencontainers.image.description="Self-hostable MCP server for the Google Ads API" \
      org.opencontainers.image.source="https://github.com/loomascale/google-ads-mcp" \
      org.opencontainers.image.licenses="MIT"
