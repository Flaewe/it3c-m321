# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY chat-service/pom.xml chat-service/pom.xml
COPY chat-service/src chat-service/src
# Tests werden hier übersprungen: Testcontainers bräuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "./mvnw test".
# Gebaut wird mit "-f <modul>/pom.xml", NICHT mit "-pl <modul> -am".
#
# Der Unterschied ist hier entscheidend: "-pl" baut über das Eltern-POM als
# Sammelprojekt, und dann muss JEDES darin eingetragene Modul auch vorhanden
# sein. Im Build-Kontext liegt aber nur dieses eine. Mit "-f" wird das
# Eltern-POM nur als Eltern gelesen -- seine Modulliste spielt dann keine
# Rolle. "-am" braucht es ohnehin nicht: die Dienste hängen nicht
# voneinander ab, es gibt bewusst kein gemeinsames Modul.
RUN mvn -q -f chat-service/pom.xml package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/chat-service/target/chat-service-0.1.0-SNAPSHOT.jar app.jar
# EXPOSE dokumentiert den Port nur. Veröffentlicht wird er NICHT —
# siehe docker-compose.yml, dort steht bewusst kein ports:-Eintrag.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
