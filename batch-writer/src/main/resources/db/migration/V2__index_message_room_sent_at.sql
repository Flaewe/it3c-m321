-- Der einzige Index auf message.
--
-- Er bedient die einzige Leseabfrage des Systems: "die letzten 50
-- Nachrichten eines Raums". Mehr Indizes gibt es mit Absicht nicht --
-- jeder weitere wuerde den Schreibweg bremsen, und genau der soll hier
-- schnell sein.
CREATE INDEX idx_message_room_sent_at ON message (room_id, sent_at DESC);
