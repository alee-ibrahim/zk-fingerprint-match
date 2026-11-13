# Build: Maven with the tests (they use synthetic data only).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

# Run: a plain JRE, as an unprivileged user (uid 1000 exists in the base image), with nothing written to disk.
FROM eclipse-temurin:21-jre
COPY --from=build /src/target/zk-fingerprint-match.jar /app/zk-fingerprint-match.jar
USER 1000:1000
EXPOSE 8080
# No perf data files, so the root file system can be read-only; heap sized from the container limit.
ENV JAVA_TOOL_OPTIONS="-XX:-UsePerfData -XX:MaxRAMPercentage=70 -Djava.awt.headless=true"
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD ["java", "-cp", "/app/zk-fingerprint-match.jar", "io.github.aleeibrahim.zkmatch.server.HealthCheck"]
ENTRYPOINT ["java", "-jar", "/app/zk-fingerprint-match.jar"]
