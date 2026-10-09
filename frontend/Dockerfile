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
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Security-header snippet included by every static location (E1).
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /build/dist /usr/share/nginx/html
# Explicit even though the base image already sets it: the image never
# runs as root, whatever a later edit adds above.
USER 101
EXPOSE 8080
