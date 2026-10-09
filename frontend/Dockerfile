FROM node:22-alpine AS build
WORKDIR /build
COPY package*.json ./
RUN npm ci
# .dockerignore keeps the host's node_modules/dist out of this COPY, so the
# clean `npm ci` install above is what actually builds the bundle.
COPY . .
RUN npm run build

# Unprivileged nginx (SUP-S04): the official non-root variant runs master
# and workers as the nginx user (uid 101) and listens on 8080; its pid file
# and cache/temp paths live under /tmp, so no root is needed anywhere.
# docker-compose maps host 3000 to container 8080.
FROM nginxinc/nginx-unprivileged:alpine
# nginx.conf is installed as a TEMPLATE: the image's own entrypoint step
# (/docker-entrypoint.d/20-envsubst-on-templates.sh) renders it into
# /etc/nginx/conf.d/default.conf at container start, substituting ONLY
# variables matching NGINX_ENVSUBST_FILTER — nginx's own $host,
# $remote_addr, ... are never touched. MODOCUMENT_API_UPSTREAM is where the
# API lives: the compose service `app:8080` by default; an ECS awsvpc task
# (one shared network namespace, no `app` hostname, 8080 already taken by
# this nginx) sets 127.0.0.1:<app port> — infra/terraform/modules/ecs-app.
ENV NGINX_ENVSUBST_FILTER=^MODOCUMENT_ \
    MODOCUMENT_API_UPSTREAM=app:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
# Proxy mode (sourced by the image entrypoint just before the template step):
# unset MODOCUMENT_TRUSTED_PROXY_CIDRS = edge mode, the template renders
# exactly the compose contract; set (ECS: the VPC CIDR, i.e. the ALB) =
# realip from the trusted ALB + its X-Forwarded-Proto, optionally the
# CloudFront-Viewer-Address (MODOCUMENT_CLIENT_IP_HEADER). Invalid input stops
# the container. Root-owned 0755: the nginx user can run it, never edit it
# (chmod needs root; the base image's USER 101 is restored below).
COPY docker-entrypoint.d/19-modocument-trusted-proxy.envsh /docker-entrypoint.d/
USER root
RUN chmod 0755 /docker-entrypoint.d/19-modocument-trusted-proxy.envsh
USER 101
# No stock server block to fall back on: if the template step ever does not
# run, nginx serves nothing rather than the SPA without its API proxy and
# security headers. (/etc/nginx is owned by the unprivileged user here.)
RUN rm -f /etc/nginx/conf.d/default.conf
# Security-header snippet included by every static location (E1).
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /build/dist /usr/share/nginx/html
# Explicit even though the base image already sets it: the image never
# runs as root, whatever a later edit adds above.
USER 101
EXPOSE 8080
