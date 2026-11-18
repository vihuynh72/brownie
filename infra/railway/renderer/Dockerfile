FROM node:24.15.0-bookworm-slim@sha256:4e6b70dd6cbfc88c8157ba19aa3d9f9cce6ba4703576d55459e45efcbc9c5f5d
WORKDIR /app
COPY infra/railway/renderer/package*.json ./
RUN npm ci --omit=dev
COPY infra/railway/renderer/*.mjs ./
USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
