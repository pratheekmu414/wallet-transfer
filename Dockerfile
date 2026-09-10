# syntax=docker/dockerfile:1

# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Warm the dependency cache on its own layer so source-only changes rebuild fast.
COPY pom.xml .
RUN mvn -B -q -Dmaven.test.skip=true dependency:go-offline

COPY src ./src
RUN mvn -B -q -Dmaven.test.skip=true clean package \
    && cp target/wallet.jar /build/app.jar

# ---- runtime stage ----------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl is only here for the container HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system wallet \
    && useradd --system --gid wallet --home /app --shell /usr/sbin/nologin wallet

WORKDIR /app
COPY --from=build /build/app.jar app.jar
RUN chown -R wallet:wallet /app

USER wallet
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
    CMD curl -fsS "http://localhost:${PORT:-8080}/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
