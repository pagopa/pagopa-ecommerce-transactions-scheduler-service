FROM openjdk:17-jdk as build
WORKDIR /workspace/app

RUN microdnf install git

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw validate -DskipTests
RUN ./mvnw dependency:copy-dependencies
# RUN ./mvnw dependency:go-offline

COPY src src
COPY eclipse-style.xml eclipse-style.xml
RUN ./mvnw install -DskipTests # --offline
RUN mkdir target/extracted && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

FROM openjdk:17-slim

RUN addgroup --system user && adduser --ingroup user --system user
USER user:user

WORKDIR /app/

ARG EXTRACTED=/workspace/app/target/extracted

COPY --from=build --chown=user ${EXTRACTED}/dependencies/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/spring-boot-loader/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/snapshot-dependencies/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/application/ ./ 
RUN true


ENTRYPOINT ["java","--enable-preview","org.springframework.boot.loader.JarLauncher"]
