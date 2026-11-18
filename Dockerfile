# One image: React build -> Spring Boot static, model bundle baked in.
FROM node:18-alpine AS web
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-17 AS api
WORKDIR /app
COPY backend/pom.xml backend/pom.xml
RUN mvn -q -f backend/pom.xml dependency:go-offline
COPY backend/ backend/
COPY --from=web /app/backend/src/main/resources/static backend/src/main/resources/static
RUN mvn -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=api /app/backend/target/dispute-shield.jar app.jar
COPY models/model_bundle.json.gz models/model_bundle.json.gz
ENV PORT=8080 JAVA_OPTS="-Xmx2g"
EXPOSE 8080
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
