# syntax=docker/dockerfile:1
# ==========================================
# STAGE 1: Build JAR with Maven & OpenJDK 21
# ==========================================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy pom.xml first to take advantage of Docker layer caching & BuildKit m2 cache
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B

# Copy source code and build package (reusing persistent /root/.m2 cache)
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -B

# ==========================================
# STAGE 2: Lightweight Runtime with JRE 21
# ==========================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Add non-root user for security & create writable pdf cache dir
RUN addgroup -S spring && adduser -S spring -G spring \
    && mkdir -p /app/data /app/pdf-cache \
    && chown -R spring:spring /app/data /app/pdf-cache

# Bundled digitized markdown regulations (406 docs, ~4.5 MB)
COPY --chown=spring:spring data/raw_markdown.zip ./data/raw_markdown.zip

# Copy built JAR from builder stage
COPY --from=builder --chown=spring:spring /app/target/*.jar app.jar

USER spring:spring

# Expose server port
EXPOSE 8080

# Environment defaults
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
