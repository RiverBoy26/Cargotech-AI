FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY certs/russian_trusted_root_ca_pem.crt /usr/local/share/ca-certificates/russian-trusted-root-ca.crt
COPY certs/russian_trusted_sub_ca_pem.crt /usr/local/share/ca-certificates/russian-trusted-sub-ca.crt
RUN update-ca-certificates && \
    keytool -importcert -noprompt -trustcacerts \
      -alias russian-trusted-root-ca \
      -file /usr/local/share/ca-certificates/russian-trusted-root-ca.crt \
      -keystore "$JAVA_HOME/lib/security/cacerts" \
      -storepass changeit && \
    keytool -importcert -noprompt -trustcacerts \
      -alias russian-trusted-sub-ca \
      -file /usr/local/share/ca-certificates/russian-trusted-sub-ca.crt \
      -keystore "$JAVA_HOME/lib/security/cacerts" \
      -storepass changeit
RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=build /workspace/target/*.jar app.jar
USER spring:spring
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
