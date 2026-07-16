FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system rippleguard \
    && useradd --system --gid rippleguard --home-dir /app --shell /usr/sbin/nologin rippleguard
WORKDIR /app
COPY target/rippleguard-loan-service-0.0.1-SNAPSHOT.jar app.jar
USER rippleguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
