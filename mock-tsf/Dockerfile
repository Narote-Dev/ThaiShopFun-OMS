FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY mock-tsf/.mvn mock-tsf/.mvn
COPY mock-tsf/mvnw mock-tsf/pom.xml mock-tsf/
COPY mock-tsf/src mock-tsf/src
COPY contracts contracts
# Change: the wrapper lives in mock-tsf/. Maven must run there so it sees pom.xml and ../contracts.
RUN chmod +x mock-tsf/mvnw && cd mock-tsf && ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/* \
  && useradd --system --uid 10001 --home /app mock
WORKDIR /app
COPY --from=build --chown=mock:mock /src/mock-tsf/target/mock-tsf-exec.jar /app/mock-tsf.jar
USER mock
EXPOSE 8090
ENV PORT=8090
ENTRYPOINT ["java", "-jar", "/app/mock-tsf.jar"]
