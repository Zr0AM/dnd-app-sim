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
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
