# ---------------------------------------------------------------------------
# The Vue app, built once and served as static files by nginx.
#
# keycloak-js and the API client read their URLs at BUILD time
# (import.meta.env.VITE_*), because the result is plain JavaScript running in
# the browser. So the URLs passed here are the ones a BROWSER uses — localhost
# ports locally, public HTTPS hosts in production — never Docker service names.
# ---------------------------------------------------------------------------
FROM node:24-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

ARG VITE_KEYCLOAK_URL=http://localhost:8190
ARG VITE_KEYCLOAK_REALM=platform
ARG VITE_KEYCLOAK_CLIENT_ID=platform-web
ARG VITE_GATEWAY_URL=http://localhost:8090
ENV VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL \
    VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM \
    VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID \
    VITE_GATEWAY_URL=$VITE_GATEWAY_URL

COPY index.html auth-callback.html vite.config.js ./
COPY src ./src
RUN npm run build

# ---------------------------------------------------------------------------
FROM nginx:1.29-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
