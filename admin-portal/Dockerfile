FROM node:24.10.0-alpine AS build

WORKDIR /workspace
RUN npm install --global npm@11.6.0
COPY admin-portal/package.json admin-portal/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY admin-portal/index.html admin-portal/tsconfig.json admin-portal/tsconfig.app.json ./
COPY admin-portal/tsconfig.node.json admin-portal/vite.config.ts ./
COPY admin-portal/src ./src
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29.1-alpine

COPY admin-portal/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

USER 101:101
EXPOSE 8080
