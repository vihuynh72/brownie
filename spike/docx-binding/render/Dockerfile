# Pinned renderer for the document-fidelity spike: a fixed Debian base plus an exact
# LibreOffice and font package version, so "what rendered this PDF" is a recorded fact,
# not whatever happened to be installed on someone's machine that day.
#
# Reproducibility note: the base image is pinned by digest, and the two packages below are
# pinned to the exact versions Debian's live mirrors resolved on 2026-09-06. That is not the
# same guarantee as the checksum-pinned JDK/Maven downloads in .toolchains/ -- `apt-get install`
# still talks to Debian's current mirrors at build time, so if these exact versions are ever
# removed from bookworm's mirrors, this build will start failing instead of silently drifting.
# A fully snapshot-pinned apt source (via snapshot.debian.org) would close that gap; it was left
# out of this spike to avoid depending on a third-party archive known for being slow/rate-limited,
# for a feasibility experiment that does not yet need to survive unattended for years.
FROM debian:bookworm-slim@sha256:88200866dfff7ea7f5cbcb6ec7c8a701889efe6fe859fe64d6990e4b07ea4171

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libreoffice-writer=4:7.4.7-1+deb12u14 \
        fonts-liberation2=2.1.5-1 \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 --shell /usr/sbin/nologin renderer
USER renderer
WORKDIR /home/renderer
