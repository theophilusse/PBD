package pbd.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks arbitrary named "channels" - scalar values that accumulate over
 * time, each at its OWN rate, under its OWN condition, independent of
 * both real time and each other. This is the generic mechanism behind
 * keyframes that specify `channel=X` (see PbdInstance.Keyframe): rather
 * than every animated property being driven by one fixed-rate clock,
 * different kinds of change can accumulate differently - "humidity"
 * rising only while it's raining, some future "trampling" channel
 * rising only when a player walks over a spot, "sunlight" accumulating
 * only in daylight, and so on. A keyframe's own `time=` field is then a
 * value ALONG that channel, not a fixed number of elapsed seconds.
 *
 * A channel with no registered Rule simply never accumulates (stays at
 * 0) - registering a rule is what makes a channel name mean anything;
 * a scene can reference a channel name in its keyframes before the
 * engine has a rule for it (harmless: it'll just never animate past its
 * time=0 pose until a rule exists).
 */
public final class ChannelTracker {

    /** Computes how fast a channel should accumulate right now, in
     * channel-units per real second - a function of whatever the engine
     * considers relevant (day/night, weather, anything else), not of
     * the channel system itself, which has no idea what "humidity" or
     * "rain" mean beyond a name and a number. */
    @FunctionalInterface
    public interface Rule {
        double ratePerSecond();
    }

    private final Map<String, Double> values = new HashMap<>();
    private final Map<String, Rule> rules = new HashMap<>();

    public void registerRule(String channelName, Rule rule) {
        rules.put(channelName, rule);
    }

    /** Advances every channel that has a registered rule by dt seconds
     * at that rule's current rate - channels with no rule are
     * unaffected (see class doc comment). */
    public void update(double dt) {
        for (var entry : rules.entrySet()) {
            double rate = entry.getValue().ratePerSecond();
            values.merge(entry.getKey(), rate * dt, Double::sum);
        }
    }

    public double get(String channelName) {
        return values.getOrDefault(channelName, 0.0);
    }

    /** Directly sets a channel's value - for a fast-forward/debug
     * control that needs to jump a channel ahead without waiting through
     * update()'s own real-time accumulation, or for restoring a
     * previously-saved value. */
    public void set(String channelName, double value) {
        values.put(channelName, value);
    }
}
