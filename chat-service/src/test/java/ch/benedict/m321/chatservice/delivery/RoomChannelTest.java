package ch.benedict.m321.chatservice.delivery;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die drei Fälle aus Spezifikation 3.6, ganz ohne Broker und HTTP:
 * Was bekommt ein Client beim Verbinden aus der Liste des Raums?
 */
class RoomChannelTest {

    /** Alle Ereignisse eines Tests gehören zum selben Raum. */
    private final UUID roomId = UUID.randomUUID();

    @Test
    void returnsNothingWithoutLastEventId() {
        RoomChannel channel = new RoomChannel();
        addEvents(channel, 3);

        List<DeliveryEvent> result = channel.eventsAfter(null);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEventsAfterAKnownIdInOrder() {
        RoomChannel channel = new RoomChannel();
        List<DeliveryEvent> added = addEvents(channel, 5);
        DeliveryEvent middle = added.get(1);
        String middleId = middle.id().toString();

        List<DeliveryEvent> result = channel.eventsAfter(middleId);

        List<DeliveryEvent> expected = added.subList(2, 5);
        assertEquals(expected, result);
    }

    @Test
    void returnsNothingAfterTheLastEvent() {
        RoomChannel channel = new RoomChannel();
        List<DeliveryEvent> added = addEvents(channel, 3);
        DeliveryEvent last = added.get(2);
        String lastId = last.id().toString();

        List<DeliveryEvent> result = channel.eventsAfter(lastId);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsWholeListForAnUnknownId() {
        RoomChannel channel = new RoomChannel();
        List<DeliveryEvent> added = addEvents(channel, 3);
        UUID unknownUuid = UUID.randomUUID();
        String unknownId = unknownUuid.toString();

        List<DeliveryEvent> result = channel.eventsAfter(unknownId);

        assertEquals(added, result);
    }

    /**
     * Nach 1001 Ereignissen muss das erste verschwunden sein. Seine ID gilt
     * dann als unbekannt, also kommt die ganze Liste: die 1000 neuesten.
     */
    @Test
    void dropsTheOldestEventAfterTheLimit() {
        RoomChannel channel = new RoomChannel();
        int count = RoomChannel.MAX_EVENTS + 1;
        List<DeliveryEvent> added = addEvents(channel, count);
        DeliveryEvent first = added.get(0);
        String firstId = first.id().toString();

        List<DeliveryEvent> result = channel.eventsAfter(firstId);

        assertEquals(RoomChannel.MAX_EVENTS, result.size());
        List<DeliveryEvent> expected = added.subList(1, count);
        assertEquals(expected, result);
    }

    /**
     * Legt so viele Ereignisse in den Raum und gibt sie in derselben
     * Reihenfolge zurück, damit der Test vergleichen kann.
     */
    private List<DeliveryEvent> addEvents(RoomChannel channel, int count) {
        List<DeliveryEvent> added = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            DeliveryEvent event = createEvent("Nachricht " + i);
            channel.add(event);
            added.add(event);
        }
        return added;
    }

    /** Baut ein Ereignis mit neuer ID. Der Inhalt des JSON spielt hier keine Rolle. */
    private DeliveryEvent createEvent(String content) {
        UUID messageId = UUID.randomUUID();
        String json = "{\"content\":\"" + content + "\"}";
        return new DeliveryEvent(messageId, roomId, json);
    }
}
