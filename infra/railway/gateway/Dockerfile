FROM nginx:1.29-alpine@sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de
COPY apps/web/nginx/security-headers-base.conf /etc/nginx/brownie-security-headers-base.conf
COPY apps/web/nginx/security-headers.conf /etc/nginx/brownie-security-headers.conf
COPY infra/railway/gateway/default.conf.template /etc/nginx/templates/default.conf.template
COPY infra/railway/gateway/api-proxy.conf /etc/nginx/brownie-api-proxy.conf
COPY infra/railway/gateway/static-proxy.conf /etc/nginx/brownie-static-proxy.conf
COPY infra/railway/gateway/19-resolver.envsh /docker-entrypoint.d/19-resolver.envsh
RUN chmod +x /docker-entrypoint.d/19-resolver.envsh && test -s /etc/ssl/certs/ca-certificates.crt
ENV NGINX_ENVSUBST_FILTER=^BROWNIE_DNS$
EXPOSE 8080
