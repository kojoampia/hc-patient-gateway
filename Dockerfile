# Builds the patient gateway image.
#
# The builder is a JDK 26 image, not the JDK 17 one this file used to name: pom.xml compiles for
# release 26, so javac 17 could not build this project at all.
#
# Tests are skipped here on purpose: they need Docker themselves (Testcontainers starts MongoDB and
# Kafka), which is not available inside an image build. `./mvnw verify` on a developer machine or in
# CI is where they run.

FROM eclipse-temurin:26-jdk AS build

WORKDIR /workspace

# Resolve dependencies in their own layer so a source-only change does not re-download the world.
# Failure is tolerated because this is only a cache warm-up — anything it misses is fetched by the
# package step below, and go-offline routinely fails on plugins it cannot fully pre-resolve.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp -DskipTests dependency:go-offline || true

# sonar-project.properties is not optional at build time: properties-maven-plugin reads it during
# the initialize phase, and the build fails outright without it.
COPY checkstyle.xml sonar-project.properties ./
COPY src/ src/

RUN ./mvnw -B -ntp -Pprod -DskipTests clean package \
    && JAR_FILE="$(ls target/*.jar | grep -Ev '(original|plain)' | head -n 1)" \
    && cp "$JAR_FILE" /tmp/app.jar

FROM eclipse-temurin:26-jre

# curl is for the healthcheck below; the JRE image ships neither curl nor wget.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring && useradd --system --gid spring spring

WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar

USER spring
EXPOSE 5503

# Readiness rather than liveness: this app registers with Consul, runs its Mongock migrations and
# connects to Kafka during startup, and readiness is what reports those as usable. Consul is
# mandatory — the app refuses to start without it.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
  CMD curl -fsS http://localhost:5503/management/health/readiness >/dev/null || exit 1

# MaxRAMPercentage rather than a fixed -Xmx, so the JVM sizes itself to whatever the container gets.
# The gateway is reactive: its default thread pool is small and CPU-bound, so no thread tuning here.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
