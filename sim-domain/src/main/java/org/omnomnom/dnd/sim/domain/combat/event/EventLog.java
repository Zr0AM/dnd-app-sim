package org.omnomnom.dnd.sim.domain.combat.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An {@link EventSink} that keeps every event in order. Not thread-safe; one per run. */
public final class EventLog implements EventSink {

    private final List<CombatEvent> events = new ArrayList<>();

    @Override
    public void accept(CombatEvent event) {
        events.add(event);
    }

    public List<CombatEvent> events() {
        return Collections.unmodifiableList(events);
    }

    public <T extends CombatEvent> List<T> of(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (CombatEvent e : events) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }
}
