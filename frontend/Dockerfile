FROM node:22-alpine AS build
WORKDIR /build
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Unprivileged nginx (SUP-S04): the official non-root variant runs the master
# process as the nginx user (uid 101) and listens on 8080 — pid file and
# cache/temp paths are already writable, so no root is needed anywhere.
# docker-compose maps host 3000 to container 8080.
FROM nginxinc/nginx-unprivileged:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 8080
