FROM eclipse-temurin:21-jdk AS build
WORKDIR /app/order-agent/backend
COPY order-agent/backend/gradlew order-agent/backend/gradlew.bat order-agent/backend/build.gradle order-agent/backend/settings.gradle ./
COPY order-agent/backend/gradle ./gradle
RUN chmod +x gradlew
COPY order-agent/backend/src ./src
COPY shared/skill-parser /app/shared/skill-parser
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system orderagent && useradd --system --gid orderagent orderagent
WORKDIR /app
COPY --from=build /app/order-agent/backend/build/libs/order-agent.jar app.jar
COPY order-agent/skills ./skills
USER orderagent
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
