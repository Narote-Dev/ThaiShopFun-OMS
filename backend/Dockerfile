FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN useradd --system --uid 10001 --home /app oms
WORKDIR /app
COPY --from=build --chown=oms:oms /src/target/oms.jar /app/oms.jar
USER oms
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["java", "-jar", "/app/oms.jar"]
