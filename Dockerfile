# ---------- Сборка ----------
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /project
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle installDist --no-daemon

# ---------- Запуск ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /project/build/install/chat-server/ /app/
EXPOSE 8080
ENTRYPOINT ["/app/bin/chat-server"]
