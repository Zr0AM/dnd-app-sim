package org.omnomnom.dnd.sim.adapter.in.web.simulate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import org.omnomnom.dnd.sim.adapter.in.web.error.RequestValidationException;
import org.omnomnom.dnd.sim.adapter.in.web.validation.ValidLevel;
import org.omnomnom.dnd.sim.application.encounter.EncounterCommand;
import org.omnomnom.dnd.sim.application.encounter.PartyMemberSpec;
import org.omnomnom.dnd.sim.application.error.SimException.FieldError;
import org.omnomnom.dnd.sim.application.evaluation.EvaluationService;
import org.omnomnom.dnd.sim.application.evaluation.GenomeInput;
import org.omnomnom.dnd.sim.application.job.OptimizeCommand;
import org.omnomnom.dnd.sim.domain.content.build.FightingStyle;
import org.omnomnom.dnd.sim.domain.content.build.Role;
import org.omnomnom.dnd.sim.domain.opt.genome.BuildClass;

/** Request bodies of the simulate endpoints, with the schema's bounds as Bean Validation constraints. */
final class SimulateRequests {

    private SimulateRequests() {}

    static final String MAP_PATTERN = "open-field|corridor-chokepoint|party-field";
    static final long MAX_SEED = 4294967295L;

    record GenomeInputBody(
            @NotNull BuildClass classSlug,
            @Size(min = 6, max = 6) List<@NotNull @Min(0) @Max(5) Integer> abilityAssignment,
            @Size(max = 100) String weaponName,
            @Size(max = 100) String armorName,
            Boolean shield,
            Boolean twoHanded,
            FightingStyle fightingStyle) {

        GenomeInput toInput() {
            return new GenomeInput(classSlug, abilityAssignment, weaponName, armorName, shield, twoHanded, fightingStyle);
        }
    }

    record PartyMemberBody(@NotNull @Pattern(regexp = "build|filler") String type, @Size(max = 64) String id, @Valid GenomeInputBody genome, Role role) {

        PartyMemberSpec toSpec(int index, List<FieldError> errors) {
            if ("build".equals(type)) {
                if (genome == null) {
                    errors.add(new FieldError("party[" + index + "].genome", "is required for a build member", "required"));
                    return null;
                }
                return new PartyMemberSpec.Build(id, genome.toInput());
            }
            if (role == null) {
                errors.add(new FieldError("party[" + index + "].role", "is required for a filler member", "required"));
                return null;
            }
            return new PartyMemberSpec.Filler(id, role);
        }
    }

    record EnemyGroupBody(@NotBlank @Size(max = 100) String monsterSlug, @Min(1) @Max(30) int count) {}

    record EncounterBody(
            @NotNull @ValidLevel Integer level,
            @NotEmpty @Size(max = 6) List<@NotNull @Valid PartyMemberBody> party,
            @Size(max = 30) List<@NotNull @Valid EnemyGroupBody> enemies,
            @Size(max = 100) String scenarioId,
            @Pattern(regexp = MAP_PATTERN) String map,
            @Min(1) @Max(2000) Integer runs,
            @Min(0) @Max(MAX_SEED) Long seed,
            @Min(1) @Max(100) Integer roundCap,
            Boolean includeLog,
            @Min(0) Integer logRun) {

        EncounterCommand toCommand() {
            List<FieldError> errors = new ArrayList<>();
            boolean hasEnemies = enemies != null && !enemies.isEmpty();
            if (hasEnemies == (scenarioId != null)) {
                errors.add(new FieldError("enemies", "give exactly one of enemies or scenarioId", "invalid-enemy-spec"));
            }
            List<PartyMemberSpec> members = new ArrayList<>();
            for (int i = 0; i < party.size(); i++) {
                PartyMemberSpec spec = party.get(i).toSpec(i, errors);
                if (spec != null) {
                    members.add(spec);
                }
            }
            if (!errors.isEmpty()) {
                throw new RequestValidationException(errors);
            }
            return new EncounterCommand(
                    level,
                    members,
                    hasEnemies ? enemies.stream().map(e -> new EncounterCommand.EnemyGroup(e.monsterSlug(), e.count())).toList() : null,
                    scenarioId,
                    map,
                    runs != null ? runs : 1,
                    seed,
                    roundCap != null ? roundCap : EncounterCommand.DEFAULT_ROUND_CAP,
                    includeLog != null && includeLog,
                    logRun != null ? logRun : 0);
        }
    }

    record EvalBody(
            @NotNull @ValidLevel Integer level,
            @NotNull @Valid GenomeInputBody genome,
            EvaluationService.Context context,
            @Size(max = 32) String role,
            @Min(1) @Max(200) Integer runs,
            @Min(0) @Max(MAX_SEED) Long seed) {

        EvaluationService.EvalCommand toCommand() {
            return new EvaluationService.EvalCommand(level, genome.toInput(),
                    context != null ? context : EvaluationService.Context.SOLO, role, runs, seed);
        }
    }

    record CampaignBody(
            @NotNull @ValidLevel Integer level,
            @NotNull @Valid GenomeInputBody genome,
            @Min(1) @Max(100) Integer days,
            @DecimalMin("0") @DecimalMax("1") Double shortRestHealFrac,
            @Min(0) @Max(MAX_SEED) Long seed) {

        EvaluationService.CampaignCommand toCommand() {
            return new EvaluationService.CampaignCommand(level, genome.toInput(), days != null ? days : 16,
                    shortRestHealFrac != null ? shortRestHealFrac : 0.5, seed);
        }
    }

    record GaBody(
            @Min(2) @Max(128) Integer populationSize,
            @Min(0) @Max(100) Integer generations,
            @Min(1) @Max(64) Integer evalRuns,
            @DecimalMin("0") @DecimalMax("1") Double mutationRate) {}

    record OptimizeBody(
            @NotNull @ValidLevel Integer level,
            @Size(max = 32) String role,
            @Size(max = 12) List<@NotNull BuildClass> classes,
            @Pattern(regexp = "solo|party") String context,
            @Pattern(regexp = "quick|standard|thorough") String preset,
            @Valid GaBody ga,
            Boolean campaign,
            @Min(0) @Max(MAX_SEED) Long seed) {

        OptimizeCommand toCommand() {
            return new OptimizeCommand(level, role, classes, preset,
                    ga == null ? null : new OptimizeCommand.GaParams(ga.populationSize(), ga.generations(), ga.evalRuns(), ga.mutationRate()),
                    "party".equals(context), campaign != null && campaign, seed);
        }
    }
}
