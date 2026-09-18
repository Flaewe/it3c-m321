# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY web-gateway/pom.xml web-gateway/pom.xml
COPY web-gateway/src web-gateway/src
# Tests werden hier übersprungen, damit der Image-Bau nicht vom Netz abhängt.
# Getestet wird vorher mit "mvn test" — die Tests des Gateways brauchen kein
# Docker, sie starten ihre eigene Keycloak-Attrappe.
RUN mvn -q -pl web-gateway -am package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/web-gateway/target/web-gateway-0.1.0-SNAPSHOT.jar app.jar
# Dies ist der einzige Dienst, dessen Port in docker-compose.yml auch
# wirklich veröffentlicht wird.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
