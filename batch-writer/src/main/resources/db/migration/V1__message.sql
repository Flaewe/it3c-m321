-- Die Tabelle, in die der batch-writer schreibt.
--
-- Die Spalten entsprechen PLANUNG.md, Abschnitt 3.7.
--
-- Kein Fremdschluessel von room_id auf eine Tabelle room: die gibt es in
-- dieser Ausbaustufe noch nicht, und der chat-service vergibt beliebige
-- Raum-Kennungen, ohne dass vorher ein Raum angelegt wurde. Ein
-- Fremdschluessel wuerde deshalb jeden Einfuegevorgang scheitern lassen.
--
-- timestamptz und nicht timestamp: der chat-service schickt die Zeit mit
-- Zeitzone. Ohne Zeitzone wuerde Postgres sie stillschweigend wegwerfen,
-- und die Reihenfolge der Nachrichten haenge an der Zeitzone des Servers.
CREATE TABLE message (
    id          uuid         PRIMARY KEY,
    room_id     uuid         NOT NULL,
    sender_id   varchar(255) NOT NULL,
    sender_name varchar(255) NOT NULL,
    content     text         NOT NULL,
    sent_at     timestamptz  NOT NULL
);
