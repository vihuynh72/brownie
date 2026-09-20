# syntax=docker/dockerfile:1.7
# The public face: the built single-page application, and the proxy that makes
# it and the API one origin.
#
# One origin is not a convenience. The session is an opaque cookie the browser
# sends back only to the origin that set it, and the identity provider's
# callback is registered for exactly one address. Two origins would mean
# either a cookie that crosses sites or a second copy of the sign-in flow.
FROM node:24.15.0-bookworm-slim@sha256:4e6b70dd6cbfc88c8157ba19aa3d9f9cce6ba4703576d55459e45efcbc9c5f5d AS build
WORKDIR /src
# The lockfile is copied first so that a source-only change does not refetch
# every dependency.
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci
COPY apps/web/ ./
RUN npm run build

FROM nginx:1.29-alpine@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de
# The image's own entrypoint fills ${...} in anything under templates/ at
# start-up, which is how one image serves whatever address it is deployed at.
COPY apps/web/nginx/security-headers.conf /etc/nginx/brownie-security-headers.conf
COPY apps/web/nginx/brownie.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist/ /usr/share/nginx/html/
EXPOSE 80 443
