# skorimako アプリ（scripts/dist.sh の成果物を詰めるだけ）
# DuckDB のネイティブのライブラリは glibc 向けのため、alpine ではなく Ubuntu の JRE を使う
FROM eclipse-temurin:25-jre
RUN groupadd --system skorimako && useradd --system --gid skorimako --home /var/lib/skorimako --create-home skorimako
COPY dist/lib /app/lib
COPY dist/static /app/static
ENV STATIC_DIR=/app/static HTTP_PORT=8080 DATA_DIR=/var/lib/skorimako
USER skorimako
VOLUME ["/var/lib/skorimako"]
EXPOSE 8080
CMD ["java", "-XX:MaxRAMPercentage=50", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED", "-cp", "/app/lib/*", "skorimako.app.Main"]
