# Application web (Expo) construite depuis le dépôt pokeApp, puis servie par Caddy avec le relais vers l'API.

# --- Construction ---
FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY --from=pokeapp package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY --from=pokeapp app.json tsconfig.json ./
COPY --from=pokeapp assets ./assets
COPY --from=pokeapp src ./src
# L'API est relayée par Caddy sur /api (même adresse que l'application)
ENV EXPO_PUBLIC_API_URL=/api EXPO_OFFLINE=1 CI=1 NODE_OPTIONS=--max-old-space-size=1536
RUN npx expo export --platform web

# --- Service ---
FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
