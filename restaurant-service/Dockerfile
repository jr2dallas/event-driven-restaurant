FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build/service

COPY api/ /build/api/
COPY restaurant-service/pom.xml ./pom.xml
RUN mvn dependency:go-offline -q

COPY restaurant-service/src ./src
RUN mvn clean package -DskipTests -q

# Stage pour récupérer le CLI Docker + plugin Compose
FROM docker:cli AS docker-tools

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
# CLI Docker (pour lancer docker compose depuis le container)
COPY --from=docker-tools /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-tools /usr/local/libexec/docker/cli-plugins/ /usr/local/libexec/docker/cli-plugins/
WORKDIR /app
COPY --from=build /build/service/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]