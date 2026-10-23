# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /build/sjm
COPY pom.xml ./
COPY modules ./modules
# Concurrent example builds share dependencies, but must never read another build's half-written Maven artifact.
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -pl modules/simple-java-mail -am -Dmaven.test.skip=true -Dlicense.skip=true -Dmaven.javadoc.skip=true install
COPY tools/integration-patterns /build/patterns
ARG APP=transaction-outbox
RUN --mount=type=cache,target=/root/.m2,sharing=locked SJM_VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout) && \
    mvn -B -f /build/patterns/pom.xml -pl "$APP" -am -Dsjm.version="$SJM_VERSION" package && \
    cp "/build/patterns/$APP/target/$APP-1.0-SNAPSHOT.jar" /build/application.jar
# Synthetic demo identity only; never use this generated key or password outside the isolated examples.
RUN keytool -genkeypair -alias partner -keyalg RSA -keysize 2048 -validity 30 -dname "CN=Local Demo,EMAILADDRESS=sender@example.test" \
    -storetype PKCS12 -keystore /build/partner.p12 -storepass local-demo-only -keypass local-demo-only

FROM eclipse-temurin:21.0.8_9-jre-jammy@sha256:db1689535962d757a5adabf57387584ed543d38c0b9d1fe870123ea362ad73b0
RUN mkdir -p /data/archive /opt/demo && chown -R 10001:10001 /data /opt/demo
WORKDIR /opt/demo
COPY --from=build /build/application.jar application.jar
COPY --from=build --chown=10001:10001 /build/partner.p12 partner.p12
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "application.jar"]
