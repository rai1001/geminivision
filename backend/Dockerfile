FROM node:20-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY tsconfig.json ./
COPY src/ ./src/

RUN npx tsc

EXPOSE ${PORT:-3000}

CMD ["node", "dist/server.js"]
