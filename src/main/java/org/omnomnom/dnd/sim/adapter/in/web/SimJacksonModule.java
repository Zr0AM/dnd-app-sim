package org.omnomnom.dnd.sim.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import java.util.function.Function;
import org.omnomnom.dnd.sim.application.ContentService;
import org.omnomnom.dnd.sim.application.EncounterResult;
import org.omnomnom.dnd.sim.application.EvaluationService;
import org.omnomnom.dnd.sim.domain.combat.CombatEvent;
import org.omnomnom.dnd.sim.domain.content.FightingStyle;
import org.omnomnom.dnd.sim.domain.content.Role;
import org.omnomnom.dnd.sim.domain.core.Coded;
import org.omnomnom.dnd.sim.domain.opt.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.Genome;
import org.omnomnom.dnd.sim.domain.opt.Interval;

/**
 * JSON wiring for the domain types, so the domain itself stays free of Jackson: enums travel as their lowercase codes,
 * combat events carry a {@code kind} discriminator (the TypeScript {@code CombatEvent} union), and intervals drop the
 * derived half-width.
 */
public final class SimJacksonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public SimJacksonModule() {
        super("dnd-app-sim");
        addSerializer(Coded.class, new CodedSerializer());
        addCoded(BuildClass.class, BuildClass::fromCode);
        addCoded(FightingStyle.class, code -> {
            for (FightingStyle s : FightingStyle.values()) {
                if (s.code().equals(code)) {
                    return s;
                }
            }
            throw new IllegalArgumentException(code);
        });
        addCoded(Role.class, Role::fromCode);
        addCoded(EvaluationService.Context.class, code -> {
            for (EvaluationService.Context c : EvaluationService.Context.values()) {
                if (c.code().equals(code)) {
                    return c;
                }
            }
            throw new IllegalArgumentException(code);
        });
        setMixInAnnotation(CombatEvent.class, CombatEventMixin.class);
        setMixInAnnotation(Interval.class, IntervalMixin.class);
        setMixInAnnotation(EvaluationService.EvalOutcome.class, OmitNulls.class);
        setMixInAnnotation(EncounterResult.MemberStats.class, OmitNulls.class);
        setMixInAnnotation(Genome.class, GenomeMixin.class);
        setMixInAnnotation(ContentService.EnemyView.class, OmitNulls.class);
        setMixInAnnotation(ContentService.ScenarioView.class, OmitNulls.class);
    }

    private <E extends Enum<E> & Coded> void addCoded(Class<E> type, Function<String, E> parse) {
        addDeserializer(type, new StdDeserializer<E>(type) {
            private static final long serialVersionUID = 1L;

            @Override
            public E deserialize(JsonParser p, DeserializationContext ctxt) {
                String code = p.getString();
                try {
                    return parse.apply(code);
                } catch (IllegalArgumentException e) {
                    return type.cast(ctxt.handleWeirdStringValue(type, code, "not one of the allowed values"));
                }
            }
        });
    }

    private static final class CodedSerializer extends StdSerializer<Coded> {
        private static final long serialVersionUID = 1L;

        CodedSerializer() {
            super(Coded.class);
        }

        @Override
        public void serialize(Coded value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(value.code());
        }
    }

    @JsonIgnoreProperties("halfWidth")
    abstract static class IntervalMixin {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract static class OmitNulls {}

    /** {@code armorName} is required and nullable (null means unarmored); {@code fightingStyle} is simply absent when unset. */
    abstract static class GenomeMixin {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        abstract FightingStyle fightingStyle();
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = CombatEvent.Initiative.class, name = "initiative"),
        @JsonSubTypes.Type(value = CombatEvent.Round.class, name = "round"),
        @JsonSubTypes.Type(value = CombatEvent.Turn.class, name = "turn"),
        @JsonSubTypes.Type(value = CombatEvent.Move.class, name = "move"),
        @JsonSubTypes.Type(value = CombatEvent.Attack.class, name = "attack"),
        @JsonSubTypes.Type(value = CombatEvent.Opportunity.class, name = "opportunity"),
        @JsonSubTypes.Type(value = CombatEvent.SpellCast.class, name = "spell"),
        @JsonSubTypes.Type(value = CombatEvent.Down.class, name = "down"),
        @JsonSubTypes.Type(value = CombatEvent.Death.class, name = "death"),
        @JsonSubTypes.Type(value = CombatEvent.DeathSave.class, name = "deathSave"),
        @JsonSubTypes.Type(value = CombatEvent.ControlDenied.class, name = "controlDenied"),
        @JsonSubTypes.Type(value = CombatEvent.ConcentrationBroken.class, name = "concentrationBroken"),
        @JsonSubTypes.Type(value = CombatEvent.Heal.class, name = "heal"),
        @JsonSubTypes.Type(value = CombatEvent.Marked.class, name = "marked"),
        @JsonSubTypes.Type(value = CombatEvent.Legendary.class, name = "legendary"),
        @JsonSubTypes.Type(value = CombatEvent.BuffApplied.class, name = "buffApplied"),
        @JsonSubTypes.Type(value = CombatEvent.BuffBoost.class, name = "buffBoost"),
        @JsonSubTypes.Type(value = CombatEvent.End.class, name = "end")
    })
    interface CombatEventMixin {}
}
