# One build, two runtime images (docker compose picks the target).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY mock-bank/pom.xml mock-bank/
COPY assistant/pom.xml assistant/
COPY mock-bank/src mock-bank/src
COPY assistant/src assistant/src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre AS mock-bank
WORKDIR /app
COPY --from=build /src/mock-bank/target/mock-bank-exec.jar app.jar
USER 10001
EXPOSE 8001
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre AS assistant
WORKDIR /app
COPY --from=build /src/assistant/target/assistant.jar app.jar
USER 10001
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]
