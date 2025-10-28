FROM amazoncorretto:21-alpine@sha256:2622b23e1a0287ab6e2a0abb0faab15f4070716dd5e6a70bebf1b1dfd48bd22a AS build
WORKDIR /workspace/app

RUN apk add --no-cache git gettext

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY settings.xml.template /tmp/
COPY dep-sha256.json .

RUN --mount=type=secret,id=GITHUB_TOKEN,env=GITHUB_TOKEN \
    mkdir -p ~/.m2 && \
    envsubst < /tmp/settings.xml.template > ~/.m2/settings.xml && \
    ./mvnw dependency:copy-dependencies
# RUN ./mvnw dependency:go-offline

COPY src src
COPY eclipse-style.xml eclipse-style.xml
COPY api-spec api-spec
RUN --mount=type=secret,id=GITHUB_TOKEN,env=GITHUB_TOKEN \
    ./mvnw install -DskipTests
RUN mkdir target/extracted && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

FROM amazoncorretto:21-alpine@sha256:2622b23e1a0287ab6e2a0abb0faab15f4070716dd5e6a70bebf1b1dfd48bd22a

RUN addgroup --system user && adduser --ingroup user --system user
USER user:user

WORKDIR /app/

ARG EXTRACTED=/workspace/app/target/extracted

# OTEL apm agent
ADD --chown=user https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar .

COPY --from=build --chown=user ${EXTRACTED}/dependencies/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/spring-boot-loader/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/snapshot-dependencies/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/application/ ./

RUN true

ENTRYPOINT ["java","-javaagent:opentelemetry-javaagent.jar","--enable-preview","org.springframework.boot.loader.launch.JarLauncher"]