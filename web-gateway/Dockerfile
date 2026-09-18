# Stufe 1: die Web-App bauen
# Das Ergebnis sind statische Dateien -- HTML, JavaScript, CSS. Sie brauchen
# keinen eigenen Container und keinen eigenen Port, sie werden gleich in das
# Jar des Gateways hineinkopiert. So bleibt es bei einem Container mit einem
# Port, wie die Vorgabe es verlangt.
FROM node:22-alpine AS ui
WORKDIR /ui
COPY web-ui/package.json web-ui/package-lock.json ./
# "npm ci" statt "npm install": es haelt sich strikt an die Lock-Datei und
# baut damit jedes Mal dasselbe.
RUN npm ci
COPY web-ui/ ./
RUN npm run build

# Stufe 2: das Gateway bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY web-gateway/pom.xml web-gateway/pom.xml
COPY web-gateway/src web-gateway/src
# Die fertige Web-App landet dort, wo Spring Boot statische Dateien sucht.
COPY --from=ui /ui/dist/ web-gateway/src/main/resources/static/
# Tests werden hier übersprungen, damit der Image-Bau nicht vom Netz abhängt.
# Getestet wird vorher mit "mvn test" — die Tests des Gateways brauchen kein
# Docker, sie starten ihre eigene Keycloak-Attrappe.
RUN mvn -q -pl web-gateway -am package -DskipTests

# Stufe 3: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/web-gateway/target/web-gateway-0.1.0-SNAPSHOT.jar app.jar
# Dies ist der einzige Dienst, dessen Port in docker-compose.yml auch
# wirklich veröffentlicht wird.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
