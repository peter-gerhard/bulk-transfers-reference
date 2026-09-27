FROM sbtscala/scala-sbt:eclipse-temurin-21.0.12_8_1.13.0_2.13.18 AS build

WORKDIR /build

COPY build.sbt ./
COPY project ./project
RUN sbt update

COPY src ./src
RUN sbt assembly

FROM eclipse-temurin:21-jre

RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app

COPY --from=build /build/target/scala-2.13/bulk-transfers.jar ./bulk-transfers.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/bulk-transfers.jar"]
