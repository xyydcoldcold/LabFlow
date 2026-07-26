FROM node:24-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./

RUN npm ci

COPY index.html tsconfig.json vite.config.ts ./
COPY src src

RUN npm run build


FROM nginxinc/nginx-unprivileged:alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=nginx:nginx /app/dist /usr/share/nginx/html

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
