FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app app && mkdir -p /app/logs && chown app:app /app/logs
COPY --from=build --chown=app:app /build/target/csgo-tradeup-proxy.jar /app/app.jar
USER app
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
