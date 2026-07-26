FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./

RUN chmod +x gradlew
RUN ./gradlew dependencies --configuration runtimeClasspath --no-daemon --quiet > /dev/null

COPY src src

RUN ./gradlew bootJar --no-daemon


FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S labflow \
    && adduser -S -G labflow -h /app labflow

WORKDIR /app

COPY --from=build --chown=labflow:labflow \
    /workspace/build/libs/backend-0.0.1-SNAPSHOT.jar \
    /app/app.jar

USER labflow

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
