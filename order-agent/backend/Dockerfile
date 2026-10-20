FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY backend/gradlew backend/gradlew.bat backend/build.gradle backend/settings.gradle ./
COPY backend/gradle ./gradle
RUN chmod +x gradlew
COPY backend/src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system orderagent && useradd --system --gid orderagent orderagent
WORKDIR /app
COPY --from=build /app/build/libs/order-agent.jar app.jar
COPY skills ./skills
USER orderagent
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
