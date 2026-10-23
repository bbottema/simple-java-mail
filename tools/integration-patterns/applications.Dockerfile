# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /build/sjm
COPY pom.xml ./
COPY modules ./modules
# Keep the shared dependency cache coherent when two examples are built at the same time.
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -pl modules/simple-java-mail,modules/open-telemetry-module -am -Dmaven.test.skip=true -Dlicense.skip=true -Dmaven.javadoc.skip=true install
COPY tools/integration-patterns /build/patterns
COPY simplejavamail.org/src/assets/journal/articles/relaydesk/examples /build/case-studies/relaydesk
COPY simplejavamail.org/src/assets/journal/articles/polar-meridian/examples /build/case-studies/polar-meridian
ARG APP=relaydesk
RUN --mount=type=cache,target=/root/.m2,sharing=locked SJM_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout) && \
    CRYPTO_VERSION=$(mvn -q help:evaluate -Dexpression=bouncycastle.version -DforceStdout) && \
    mvn -B -f /build/patterns/pom.xml -pl "$APP" -am -Dsjm.version="$SJM_VERSION" -Dbouncycastle.version="$CRYPTO_VERSION" -Dcase.study.sources=/build/case-studies package && \
    cp "/build/patterns/$APP/target/$APP-1.0-SNAPSHOT.jar" /build/application.jar
# Development-only TLS and signing identities. No host trust store or production key is used.
RUN keytool -genkeypair -alias server -keyalg RSA -keysize 2048 -validity 30 -dname CN=local-demo \
    -ext SAN=dns:smtp-a,dns:smtp-b,dns:urgent-a,dns:urgent-b,dns:bulk,dns:gateway \
    -storetype PKCS12 -keystore /build/server.p12 -storepass local-demo-only && \
    keytool -exportcert -rfc -alias server -keystore /build/server.p12 -storepass local-demo-only -file /build/server.crt && \
    openssl pkcs12 -in /build/server.p12 -passin pass:local-demo-only -nodes -out /build/server.pem && \
    keytool -genkeypair -alias partner -keyalg RSA -keysize 2048 -validity 30 -dname CN=Synthetic-Partner \
    -storetype PKCS12 -keystore /build/partner.p12 -storepass local-demo-only && \
    keytool -exportcert -rfc -alias partner -keystore /build/partner.p12 -storepass local-demo-only -file /build/partner.crt
FROM eclipse-temurin:21.0.8_9-jre-jammy@sha256:db1689535962d757a5adabf57387584ed543d38c0b9d1fe870123ea362ad73b0 AS cloud
RUN mkdir -p /data/archive /opt/demo && chown -R 10001:10001 /data /opt/demo
WORKDIR /opt/demo
COPY --from=build /build/application.jar application.jar
COPY --from=build --chown=10001:10001 /build/partner.p12 /build/partner.crt ./
COPY --from=build /build/case-studies/polar-meridian/dispatch-limits.sql ./
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "application.jar"]

# Only local fixtures need a development SMTP/HTTPS identity. It must not become a trusted CA in the AWS worker.
FROM cloud AS local
USER 0:0
COPY --from=build --chown=10001:10001 /build/server.p12 /build/server.pem /build/server.crt ./
RUN keytool -importcert -noprompt -alias local-demo-only -file server.crt -cacerts -storepass changeit
USER 10001:10001
