# SmartCodeReview as a native executable - no local GraalVM needed.
#   docker compose up --build        (see docker-compose.yml)
#   ./release.sh X.Y.Z               (builds, tags and pushes a release - docs/08-versionierung.md)
#   docker build --build-arg VERSION=0.3.0 -t culmi/smart-code-review:0.3.0 .
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
# runs the unit tests before compiling natively
RUN chmod +x mvnw && ./mvnw -B -q -Dnative package

FROM quay.io/quarkus/ubi9-quarkus-micro-image:2.0
ARG VERSION=dev
LABEL org.opencontainers.image.title="SmartCodeReview"       org.opencontainers.image.description="Code reviews by meaning instead of by file: quick wins and data flows for GitHub pull requests and GitLab merge requests"       org.opencontainers.image.version="${VERSION}"       org.opencontainers.image.source="https://github.com/cyrilleulmi/smart-code-review"
WORKDIR /work/
RUN mkdir -p /data && chown 1001:root /work /data && chmod g+rwX /work /data
COPY --from=native --chown=1001:root --chmod=0755 /code/target/*-runner /work/application
ENV SCR_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
USER 1001
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
