# Build stage — compiles the jar inside the image itself, so this Dockerfile
# is self-contained and works the same way locally and on a CI/CD platform
# like Render, which only has the git repo (no pre-built target/ dir, since
# that's correctly gitignored).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# Runtime stage — just the JRE + the jar, no build tooling.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
