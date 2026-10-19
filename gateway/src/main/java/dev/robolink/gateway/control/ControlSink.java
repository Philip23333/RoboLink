package dev.robolink.gateway.control;

import dev.robolink.protocol.v1.Envelope;
import java.util.function.Consumer;

/** One completion at a time keeps the network event-loop write queue bounded. */
public interface ControlSink {
    boolean active();
    boolean writable();
    void write(Envelope message, Consumer<Boolean> completed);
    void close();
}
