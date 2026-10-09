package org.omnomnom.dnd.sim.domain.opt.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.content.build.Fillers;
import org.omnomnom.dnd.sim.domain.content.build.Role;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.scenario.PartyScenarios;
import org.omnomnom.dnd.sim.domain.scenario.PartyTemplate;
import org.omnomnom.dnd.sim.domain.scenario.Scenario;
import org.omnomnom.dnd.sim.domain.scenario.ScenarioLibrary;

/** Edge cases and invariants of the evaluators that the reference-parity comparison does not reach. */
class EvaluatorTest {

    static SqliteContentSource source;
    static MonsterCatalog monsters;
    static Map<Role, Fillers.Filler> fillers;
    static List<Scenario> level3;

    @BeforeAll
    static void open() {
        source = SqliteContentSource.open();
        monsters = MonsterCatalog.load(source);
        fillers = Fillers.load(source, 5);
        level3 = ScenarioLibrary.load(monsters, source.xpByChallengeRating(), 3);
    }

    @AfterAll
    static void close() {
        source.close();
    }

    private static Function<String, Combatant> tank() {
        return id -> fillers.get(Role.TANK).make(id, Side.PARTY, Cell.of(0, 0));
    }

    @Test
    void defaultsAreSixteenSoloRunsAndTwelvePartyRunsPerScenario() {
        EvalResult solo = SoloEvaluator.evaluate(tank(), level3.subList(0, 2));
        assertThat(solo.runs()).isEqualTo(2 * 16);

        PartyEvaluator.Harness harness = new PartyEvaluator.Harness(fillers, Map.of(1, PartyScenarios.load(monsters, 1, 5)));
        PartyTemplate single = new PartyTemplate("solo", List.of(Role.TANK), Role.TANK, 1);
        PartyEvalResult party = PartyEvaluator.evaluate(tank(), harness, Role.TANK, List.of(single), 12);
        assertThat(party.runs()).isEqualTo(2 * 12);
        assertThat(PartyEvaluator.evaluate(tank(), new PartyEvaluator.Harness(fillers, Map.of()))).extracting(PartyEvalResult::runs)
                .isEqualTo(0);
        // The default evaluation spans all three reference parties, 2 scenarios each, 12 runs each.
        PartyEvaluator.Harness full = PartyEvaluator.Harness.load(fillers, monsters, 5);
        assertThat(PartyEvaluator.evaluate(tank(), full).runs()).isEqualTo(3 * 2 * 12);
    }

    @Test
    void noScenariosGivesAnEmptyResultWithTheUninformativeInterval() {
        EvalResult r = SoloEvaluator.evaluate(tank(), List.of(), 5);
        assertThat(r.runs()).isZero();
        assertThat(r.winRate()).isZero();
        assertThat(r.ci().winRate().lo()).isZero();
        assertThat(r.ci().winRate().hi()).isEqualTo(1);
    }

    @Test
    void oneRunReportsItsOwnOutcome() {
        // A level-5 Champion tank beats a pair of goblins in the first CRN run.
        Scenario goblins = level3.get(0);
        EvalResult r = SoloEvaluator.evaluate(tank(), List.of(goblins), 1);
        assertThat(r.runs()).isEqualTo(1);
        assertThat(r.winRate()).isEqualTo(1.0);
        assertThat(r.avgHpFracOnWin()).isPositive();
        assertThat(r.fitness()).isGreaterThan(100 * 1.0 - 50 * 0.1 - 1);
    }

    @Test
    void aSoloPartyOfOneHasNoAlliesSoTheyAreAllAlive() {
        PartyEvaluator.Harness harness = new PartyEvaluator.Harness(fillers, Map.of(1, PartyScenarios.load(monsters, 1, 5)));
        PartyTemplate single = new PartyTemplate("solo", List.of(Role.TANK), Role.TANK, 1);
        PartyEvalResult r = PartyEvaluator.evaluate(tank(), harness, Role.TANK, List.of(single), 1);
        assertThat(r.runs()).isEqualTo(2);
        assertThat(r.avgAlliesAliveFrac()).isEqualTo(1.0);
        assertThat(r.winRate()).isBetween(0.0, 1.0);
    }

    @Test
    void anUnderLeveledPartyLosesAlliesToTheBoss() {
        // Level-5 characters against the level-17 encounters: the lone ally does not always survive.
        Map<Role, Fillers.Filler> high = fillers;
        PartyTemplate duo = new PartyTemplate("duo", List.of(Role.TANK, Role.HEALER), Role.TANK, 1);
        PartyEvaluator.Harness harness = new PartyEvaluator.Harness(high, Map.of(2, PartyScenarios.load(monsters, 2, 17)));
        Function<String, Combatant> hero = id -> high.get(Role.TANK).make(id, Side.PARTY, Cell.of(0, 0));
        PartyEvalResult r = PartyEvaluator.evaluate(hero, harness, Role.TANK, List.of(duo), 6);
        assertThat(r.runs()).isEqualTo(12);
        // With one ally, the fraction alive is 0 or 1 per run; the adult dragon and giants kill some.
        assertThat(r.avgAlliesAliveFrac()).isLessThan(1.0).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void aFullPartyReportsTheFractionOfAlliesAlive() {
        PartyEvaluator.Harness harness = PartyEvaluator.Harness.load(fillers, monsters, 5);
        PartyEvalResult r = PartyEvaluator.evaluate(tank(), harness, Role.TANK, List.of(PartyTemplate.R4), 1);
        assertThat(r.runs()).isEqualTo(2);
        assertThat(r.avgAlliesAliveFrac()).isBetween(0.0, 1.0);
    }

    @Test
    void objectivesAreOrientedHigherIsBetterInTheDocumentedOrder() {
        EvalResult solo = new EvalResult(0, 0.5, 0, 0.4, 30, 6, 9, 2, 10, null);
        assertThat(Objectives.of(solo)).containsExactly(0.5, 30, 0.4, -9, 2, 0);
        PartyEvalResult party = new PartyEvalResult(0.5, 30, 4, 3, 7, 2, 0.4, 9, 1, 10, null);
        assertThat(Objectives.of(party)).containsExactly(0.5, 30, 0.4, -9, 2, 7);
        assertThat(Objectives.NAMES).containsExactly("reliability", "offense", "survival", "efficiency", "control", "support");
    }
}
