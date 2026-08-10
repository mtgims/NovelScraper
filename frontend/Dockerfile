# NovelScraper frontend — Next.js. It proxies /api/* to the backend, so the browser
# only ever talks to one origin (no CORS). `next.config.mjs` bakes BACKEND_ORIGIN
# into the rewrite at BUILD time, hence the build ARG.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG BACKEND_ORIGIN=http://backend:8000
ENV BACKEND_ORIGIN=$BACKEND_ORIGIN
RUN npm run build

FROM node:22-alpine AS run
WORKDIR /app
ENV NODE_ENV=production BACKEND_ORIGIN=http://backend:8000
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/package.json ./package.json
COPY --from=build /app/next.config.mjs ./next.config.mjs
EXPOSE 3000
CMD ["npx", "next", "start", "-p", "3000"]
