# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY batch-writer/src batch-writer/src
# Tests werden hier übersprungen: Testcontainers bräuchte einen
# Docker-Daemon INNERHALB des Builds. Getestet wird vorher mit
# "./mvnw clean test".
#
# Gebaut wird mit "-f <modul>/pom.xml", NICHT mit "-pl <modul> -am".
#
# Der Unterschied ist hier entscheidend: "-pl" baut über das Eltern-POM als
# Sammelprojekt, und dann muss JEDES darin eingetragene Modul auch vorhanden
# sein. Im Build-Kontext liegt aber nur dieses eine. Mit "-f" wird das
# Eltern-POM nur als Eltern gelesen -- seine Modulliste spielt dann keine
# Rolle. "-am" braucht es ohnehin nicht: die Dienste hängen nicht
# voneinander ab, es gibt bewusst kein gemeinsames Modul.
RUN mvn -q -f batch-writer/pom.xml package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/batch-writer/target/batch-writer-0.1.0-SNAPSHOT.jar app.jar
# KEIN EXPOSE und kein ports:-Eintrag in docker-compose.yml. Dieser Dienst
# hat gar keine Schnittstelle, die jemand aufrufen könnte -- seine einzige
# Eingabe ist die Queue.
ENTRYPOINT ["java", "-jar", "app.jar"]
