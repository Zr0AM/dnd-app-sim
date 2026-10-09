# syntax=docker/dockerfile:1

# Build the sim-service fat jar with the Gradle wrapper (the toolchain pins JDK 21).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --version --no-daemon
COPY buildSrc buildSrc
COPY config config
COPY sim-domain sim-domain
COPY sim-application sim-application
COPY sim-content sim-content
COPY sim-json sim-json
COPY sim-reports sim-reports
COPY sim-service sim-service
RUN --mount=type=cache,target=/root/.gradle/caches \
    ./gradlew :sim-service:bootJar --no-daemon \
    && find sim-service/build/libs -name 'sim-service-*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:21-jre
RUN groupadd --system sim && useradd --system --gid sim --no-create-home sim
WORKDIR /app
COPY --from=build /app.jar app.jar
USER sim
ENV SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
