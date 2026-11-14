package ch.benedict.m321.chatservice.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Der Live-Stream nach aussen: GET /stream?roomId=... (Spezifikation 1.2).
 *
 * Ein Client öffnet die Verbindung einmal und lässt sie offen. Jede neue
 * Nachricht seines Raums kommt dann als Server-Sent Event, ohne dass er
 * nachfragen muss.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class StreamController {

    /**
     * 0 heisst: keine Wartezeit. Der Stream bleibt offen, bis der Client
     * trennt oder die Instanz endet (1.2). Ohne diese Angabe gilt die
     * Vorgabe des Servers, bei Tomcat etwa 30 Sekunden, und die Clients in
     * S3 und S6 würden mitten im Test getrennt.
     */
    private static final long NO_TIMEOUT = 0L;

    private final RoomChannels roomChannels;

    /**
     * Öffnet einen Stream für einen Raum.
     *
     * Die Kopfzeile Last-Event-ID schickt ein Client, der sich nach einer
     * Unterbrechung neu verbindet (3.6). Wir nehmen sie als String, nicht
     * als UUID: Eine unbekannte oder unsinnige ID ergibt laut 3.6 die ganze
     * Liste und keinen Fehler 400. Fehlt sie, ist sie null.
     *
     * Bevor der Client eingetragen wird, sagen wir Spring, was beim Ende der
     * Verbindung passieren soll: ihn wieder austragen. Ob die Verbindung
     * sauber endet, abläuft oder abbricht, spielt keine Rolle.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam UUID roomId,
                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        RoomChannel channel = roomChannels.channelFor(roomId);

        emitter.onCompletion(() -> channel.unsubscribe(emitter));
        emitter.onTimeout(() -> channel.unsubscribe(emitter));
        emitter.onError(error -> channel.unsubscribe(emitter));

        channel.subscribe(emitter, lastEventId);
        log.debug("Client subscribed to room {} with Last-Event-ID {}", roomId, lastEventId);
        return emitter;
    }
}
