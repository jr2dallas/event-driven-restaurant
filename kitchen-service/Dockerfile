FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build/service

COPY api/ /build/api/
COPY kitchen-service/pom.xml ./pom.xml
RUN mvn dependency:go-offline -q

COPY kitchen-service/src ./src
RUN mvn clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/service/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]