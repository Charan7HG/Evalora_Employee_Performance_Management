# syntax=docker/dockerfile:1

# =============================================================================
# Evalora frontend image
#
# Build from the frontend/ directory:
#
#   docker build -t evalora-frontend ./frontend
#
# Only build-time variables belong here. Never copy the root .env into the
# image; it contains local backend secrets.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1 - build the React/Vite production bundle
# -----------------------------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /app

# Install the exact dependency tree recorded in package-lock.json.
COPY package.json package-lock.json ./
RUN npm ci

# Vite inlines this value into the bundle at build time.
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

# Copy only the files needed for the production build (no node_modules,
# no local .env files, no dist output).
COPY index.html vite.config.js ./
COPY public ./public
COPY src ./src

# Existing production build command from package.json -> dist/.
RUN npm run build

# -----------------------------------------------------------------------------
# Stage 2 - serve the static bundle with Nginx
# -----------------------------------------------------------------------------
FROM nginx:alpine AS runtime

# Nginx's standard static web root.
COPY --from=build /app/dist /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf

# The base image already starts Nginx in the foreground.
EXPOSE 80

# nginx:alpine includes BusyBox wget. The SPA index is served for "/".
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:80/ || exit 1
