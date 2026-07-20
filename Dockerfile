FROM eclipse-temurin:17-jre-jammy
ARG OCI_REVISION
ARG OCI_SOURCE
LABEL org.opencontainers.image.revision="${OCI_REVISION}" \
      org.opencontainers.image.source="${OCI_SOURCE}"
RUN test -n "${OCI_REVISION}" \
    && test "${OCI_REVISION}" != "unknown" \
    && test -n "${OCI_SOURCE}" \
    && test "${OCI_SOURCE}" != "unknown"
RUN groupadd --system rippleguard \
    && useradd --system --gid rippleguard --home-dir /app --shell /usr/sbin/nologin rippleguard
WORKDIR /app
COPY target/rippleguard-loan-service-0.0.1-SNAPSHOT.jar app.jar
USER rippleguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
