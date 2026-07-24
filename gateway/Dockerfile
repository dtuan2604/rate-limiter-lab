FROM gradle:8.14.3-jdk21-alpine AS build

WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY gateway/build.gradle.kts gateway/build.gradle.kts
COPY gateway/config gateway/config
COPY gateway/src gateway/src
RUN gradle :gateway:bootJar --no-daemon

FROM eclipse-temurin:21.0.8_9-jre-alpine

WORKDIR /app
COPY --from=build --chown=10001:0 /workspace/gateway/build/libs/gateway-0.0.0.jar app.jar

USER 10001:0
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
