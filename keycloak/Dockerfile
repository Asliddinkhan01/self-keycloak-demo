# ---------------------------------------------------------------------------
# Keycloak with the OneID identity provider built in.
#
# The stock image cannot be used once a provider jar is involved: Keycloak scans
# providers at BUILD time, not at startup, so the jar has to be present when
# `kc.sh build` runs. That is why this image is a build artefact of this
# repository rather than quay.io/keycloak/keycloak straight off the shelf.
#
# Built by docker compose with the REPOSITORY ROOT as its context, so the jar is
# taken straight from Maven's output and a rebuilt provider cannot be left
# behind by a forgotten copy step. The root .dockerignore admits that one file
# and nothing else. Build the jar first:
#
#   mvn -pl oneid-identity-provider -am -DskipTests package
# ---------------------------------------------------------------------------
FROM quay.io/keycloak/keycloak:26.1

# The provider jar. Keycloak loads every jar in this directory.
COPY --chown=keycloak:root oneid-identity-provider/target/oneid-identity-provider-*.jar /opt/keycloak/providers/

# Tell the build step which database this server will use, so the JDBC driver
# and Liquibase changelogs are prepared now rather than on every boot.
ENV KC_DB=postgres

# Scans providers, prepares the database layer, and writes an optimised server.
# Without this the jar is simply ignored and the provider never appears.
RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
