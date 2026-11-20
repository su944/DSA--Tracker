# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp verify \
    && cp target/dsa-tracker-0.1.0-SNAPSHOT.jar /build/tracker.jar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl sqlite3 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app /data /backups \
    && touch /data/.volume-initialized /backups/.volume-initialized \
    && chown -R 10001:10001 /data /backups \
    && chmod 700 /data /backups
WORKDIR /app
COPY --from=build /build/tracker.jar ./tracker.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/tracker.jar"]
