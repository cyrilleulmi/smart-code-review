# SmartCodeReview as a native executable - no local GraalVM needed.
#   docker compose up --build        (see docker-compose.yml)
# Stage 1 builds the Angular UI, stage 2 compiles the Quarkus backend (with the UI inside) to a
# native binary using the Mandrel builder image, stage 3 is a minimal runtime image.

FROM node:24-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# angular.json writes into ../backend/src/main/resources/META-INF/resources
RUN mkdir -p /src/backend/src/main/resources && npx ng build

FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS native
COPY --chown=quarkus:quarkus backend/ /code/
COPY --from=frontend --chown=quarkus:quarkus /src/backend/src/main/resources/META-INF/resources /code/src/main/resources/META-INF/resources
WORKDIR /code
RUN chmod +x mvnw && ./mvnw -B -q -Dnative -DskipTests package

FROM quay.io/quarkus/ubi9-quarkus-micro-image:2.0
WORKDIR /work/
RUN mkdir -p /data && chown 1001:root /work /data && chmod g+rwX /work /data
COPY --from=native --chown=1001:root --chmod=0755 /code/target/*-runner /work/application
ENV SCR_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
USER 1001
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
