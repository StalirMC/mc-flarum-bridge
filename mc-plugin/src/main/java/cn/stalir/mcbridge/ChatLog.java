package cn.stalir.mcbridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small ring of the most recent public chat lines, kept per player.
 *
 * Its only purpose is to let a player report carry what the reported player
 * actually said, rather than only the reporter's summary. That shapes everything
 * here:
 *
 * <ul>
 *   <li>Only public chat is ever handed to {@link #record}; the platforms do not
 *       feed private messages or commands into it.</li>
 *   <li>Each player keeps a bounded number of lines and only a bounded number of
 *       players are tracked, so this cannot grow with uptime.</li>
 *   <li>Names are matched case-insensitively, because the name arrived from a
 *       {@code /report} command and is therefore whatever the reporter typed.</li>
 *   <li>{@link #recent} hands back a copy, so no caller can modify what is stored
 *       - and nothing here is ever delivered anywhere on its own.</li>
 * </ul>
 *
 * The capacity is fixed at the largest window the configuration can ask for, so a
 * reload that shortens the window takes effect immediately without throwing away
 * what was already recorded.
 *
 * Thread safe: chat arrives on an asynchronous event thread while the report
 * request runs on the platform's pool.
 */
public final class ChatLog {

    /** Cap on tracked players, so a busy or hostile server cannot grow this. */
    private static final int MAX_PLAYERS = 500;

    /** Cap on one stored line, so a single oversized message cannot bloat a report. */
    private static final int MAX_LINE_LENGTH = 256;

    private final int capacityPerPlayer;
    private final Map<String, Deque<String>> lines = new LinkedHashMap<>();

    public ChatLog(int capacityPerPlayer) {
        this.capacityPerPlayer = Math.max(0, capacityPerPlayer);
    }

    /** Record one public chat line; blank text is ignored. */
    public synchronized void record(String playerName, String text) {
        if (capacityPerPlayer == 0 || playerName == null || text == null) {
            return;
        }

        // A chat message should not contain a line break, but a plugin or a proxy
        // can inject one, and a stored newline would forge an extra line in the
        // transcript a moderator reads.
        String line = text.replace('\n', ' ').replace('\r', ' ').trim();

        if (line.isEmpty()) {
            return;
        }

        if (line.length() > MAX_LINE_LENGTH) {
            line = line.substring(0, MAX_LINE_LENGTH);
        }

        Deque<String> player = lines.get(key(playerName));

        if (player == null) {
            if (lines.size() >= MAX_PLAYERS) {
                // Insertion ordered, so the eldest key is the player who has been
                // silent for longest.
                lines.remove(lines.keySet().iterator().next());
            }

            player = new ArrayDeque<>();
            lines.put(key(playerName), player);
        }

        player.addLast(line);

        while (player.size() > capacityPerPlayer) {
            player.removeFirst();
        }
    }

    /**
     * The last {@code limit} recorded lines for a player, oldest first.
     *
     * @return an empty list when nothing was recorded, which is the normal case
     *         for a player who has not spoken
     */
    public synchronized List<String> recent(String playerName, int limit) {
        Deque<String> player = playerName == null ? null : lines.get(key(playerName));

        if (player == null || limit <= 0) {
            return List.of();
        }

        List<String> recent = new ArrayList<>(player);

        return List.copyOf(recent.subList(Math.max(0, recent.size() - limit), recent.size()));
    }

    /** How many lines are held for a player; used by the runtime self test. */
    public synchronized int size(String playerName) {
        Deque<String> player = playerName == null ? null : lines.get(key(playerName));

        return player == null ? 0 : player.size();
    }

    /** Drop everything; used by the runtime self test. */
    public synchronized void clear() {
        lines.clear();
    }

    private static String key(String playerName) {
        return playerName.trim().toLowerCase(Locale.ROOT);
    }
}
