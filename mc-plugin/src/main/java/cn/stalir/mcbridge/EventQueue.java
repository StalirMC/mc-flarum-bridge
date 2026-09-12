package cn.stalir.mcbridge;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, thread-safe buffer of gameplay events waiting to be delivered.
 *
 * Events are dropped oldest-first once the buffer is full, so a forum outage
 * can never exhaust the server's memory.
 */
public final class EventQueue {

    private final ConcurrentLinkedDeque<JsonObject> deque = new ConcurrentLinkedDeque<>();
    private final AtomicLong dropped = new AtomicLong();
    private final int capacity;

    public EventQueue(int capacity) {
        this.capacity = Math.max(10, capacity);
    }

    public void add(JsonObject event) {
        if (event == null) {
            return;
        }

        while (deque.size() >= capacity) {
            if (deque.pollFirst() != null) {
                dropped.incrementAndGet();
            }
        }

        deque.addLast(event);
    }

    public int size() {
        return deque.size();
    }

    public long droppedCount() {
        return dropped.get();
    }

    /**
     * Remove up to {@code max} events, oldest first.
     */
    public List<JsonObject> drain(int max) {
        List<JsonObject> batch = new ArrayList<>(Math.min(max, 64));
        JsonObject next;

        while (batch.size() < max && (next = deque.pollFirst()) != null) {
            batch.add(next);
        }

        return batch;
    }

    /**
     * Put a failed batch back at the front of the queue, preserving order.
     */
    public void requeue(List<JsonObject> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        for (int index = events.size() - 1; index >= 0; index--) {
            while (deque.size() >= capacity) {
                if (deque.pollLast() != null) {
                    dropped.incrementAndGet();
                }
            }

            deque.addFirst(events.get(index));
        }
    }

    public void clear() {
        deque.clear();
    }
}
