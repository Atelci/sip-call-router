FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/sip-call-router-*.jar app.jar
EXPOSE 8080 5060/udp
ENTRYPOINT ["java", "-jar", "app.jar"]
