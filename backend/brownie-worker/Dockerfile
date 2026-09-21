# syntax=docker/dockerfile:1.7
# The worker: claims jobs, runs the sweeps, and is the only thing that deletes
# a stored file. It needs no container runtime and no inbound port.
FROM eclipse-temurin:21-jdk-noble@sha256:4d271cd5e0624598cf563342f47281b09cb364bc13acbbd7251f49f83470018d AS build
WORKDIR /src
COPY backend/ ./backend/
WORKDIR /src/backend
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl brownie-worker -am -DskipTests package

FROM eclipse-temurin:21-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system --gid 10101 brownie \
    && useradd --system --uid 10101 --gid 10101 --home /opt/brownie brownie
COPY --from=build --chown=brownie:brownie /src/backend/brownie-worker/target/brownie-worker-*.jar /opt/brownie/brownie-worker.jar
USER brownie:brownie
WORKDIR /opt/brownie
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65"
ENTRYPOINT ["java", "-jar", "/opt/brownie/brownie-worker.jar"]
