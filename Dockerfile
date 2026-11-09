# --- Build ---
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN gradle buildFatJar --no-daemon -x test

# --- Runtime ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/pokeapi-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
