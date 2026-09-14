# ---------------------------------------------------------------------------
# One image recipe for every Spring Boot application in this repository:
# api-gateway, user-service, organization-service, cadastral-service and
# mock-oneid.
#
# docker compose builds it once per module, with that module's directory as
# the build context, so `target/` below is that module's own target directory.
#
# The jar is built by Maven on the host first:
#
#   mvn -DskipTests package
#
# Building inside Docker instead would download every Maven dependency again
# for each of the five images, and the jar would no longer be the exact
# artefact the tests ran against.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# Nothing in these services needs root, so nothing in them runs as root.
RUN groupadd --system app \
 && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

# The executable jar only. spring-boot-maven-plugin's repackage leaves the
# plain jar beside it as *.jar.original, which this pattern does not match.
COPY target/*.jar app.jar

USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
