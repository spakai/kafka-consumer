FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -Pobservability -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -Pobservability -DskipTests package dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/kafka-pooled-transactional-producer-*.jar app.jar
COPY --from=build /workspace/target/dependency dependency
EXPOSE 9404
ENTRYPOINT ["java", "-cp", "app.jar:dependency/*", "com.kafka.producer.observability.ObservabilityDemo"]
