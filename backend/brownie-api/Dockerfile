# syntax=docker/dockerfile:1.7
# The API, and with it the launcher that starts a renderer.
#
# Base images are pinned by the content address of the multi-architecture
# index, not by a tag, because a tag can be moved. The index rather than one
# platform's manifest, so that the same file builds on the deployment's own
# architecture and on a developer's.
# The build stage compiles only this module and what it needs; tests are not
# run here, because they are run by the repository's own checks before an
# image is ever built.
FROM eclipse-temurin:21-jdk-noble@sha256:4d271cd5e0624598cf563342f47281b09cb364bc13acbbd7251f49f83470018d AS build
WORKDIR /src
COPY backend/ ./backend/
WORKDIR /src/backend
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl brownie-api -am -DskipTests package

# The Docker client, and nothing else from this image. The API starts one
# disposable container per render and needs a client to do it; it does not
# need a daemon of its own.
FROM docker:28-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d AS docker-client

FROM eclipse-temurin:21-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f
# curl is here so that the container can answer "are you ready" to whatever is
# waiting for it, and so that an operator inside it can ask the same question.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=docker-client /usr/local/bin/docker /usr/local/bin/docker

# A fixed, unprivileged account. The staging directory has to exist at the
# same absolute path inside this container and on the host: the renderer's
# input and output are bind mounts, and a bind mount is resolved by the
# daemon on the host, not inside the process that asked for it. A path that
# exists only in here would silently mount an empty directory.
RUN groupadd --system --gid 10100 brownie \
    && useradd --system --uid 10100 --gid 10100 --home /opt/brownie brownie \
    && mkdir -p /var/lib/brownie/render \
    && chown brownie:brownie /var/lib/brownie/render
COPY --from=build --chown=brownie:brownie /src/backend/brownie-api/target/brownie-api-*.jar /opt/brownie/brownie-api.jar
USER brownie:brownie
WORKDIR /opt/brownie

# MaxRAMPercentage rather than a fixed heap, so the container's own memory
# limit is what decides the heap and the two cannot disagree.
#
# The staging directory is named as its own setting rather than by moving the
# whole temporary directory: everything else that wants a temporary file, the
# web server included, should keep using the container's own, which is not a
# shared mount and does not have to be writable by anyone else.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65" \
    BROWNIE_RENDER_STAGING_DIR="/var/lib/brownie/render"
EXPOSE 8081 8090
ENTRYPOINT ["java", "-jar", "/opt/brownie/brownie-api.jar"]
