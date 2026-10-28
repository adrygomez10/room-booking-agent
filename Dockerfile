FROM maven:3.9.12-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY config config
COPY src src
RUN mvn -B verify

FROM eclipse-temurin:25-jre
RUN useradd --uid 10001 --create-home app && mkdir -p /app/data && chown -R app:app /app
WORKDIR /app
COPY --from=build /build/target/room-booking-agent-1.0.0.jar app.jar
ENV BIND_ADDRESS=0.0.0.0 PORT=8080 DB_URL=jdbc:h2:file:/app/data/bookings
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
