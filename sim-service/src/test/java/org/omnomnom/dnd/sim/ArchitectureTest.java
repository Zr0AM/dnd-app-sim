package org.omnomnom.dnd.sim;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * The layer rules, checked across every module: the service's test classpath holds all of them. Gradle already stops a
 * module from compiling against one it does not declare; these rules also cover the packages inside each module.
 */
@AnalyzeClasses(packages = "org.omnomnom.dnd.sim",
        importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.DoNotIncludeTestFixtures.class})
class ArchitectureTest {

    private static final String ROOT = "org.omnomnom.dnd.sim";

    /**
     * The most other project classes one class of the combat engine may use. This is a ratchet, set at what the largest
     * class (Combatant) uses today: lower it when a class is split, never raise it. SonarCloud's "Monster Class" rule
     * (limit 20) counts differently and stays the authority.
     */
    private static final int MAX_COLLABORATORS = 25;

    /** Counts the distinct other classes of this project a class refers to, nested classes counted as their outer class. */
    private static ArchCondition<JavaClass> useAtMostProjectClasses(int max) {
        return new ArchCondition<>("use at most " + max + " other classes of this project") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String self = outermost(javaClass.getName());
                long used = javaClass.getDirectDependenciesFromSelf().stream()
                        .map(dependency -> outermost(dependency.getTargetClass().getName()))
                        .filter(name -> name.startsWith(ROOT) && !name.equals(self))
                        .distinct()
                        .count();
                events.add(new SimpleConditionEvent(javaClass, used <= max,
                        javaClass.getName() + " uses " + used + " other project classes (limit " + max + ")"));
            }
        };
    }

    private static String outermost(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    /** Test fixtures are published by the library modules for each other's tests; they are not production code. */
    static final class DoNotIncludeTestFixtures implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("/testFixtures/") && !location.contains("-test-fixtures.jar");
        }
    }

    /** Guards the rules that allow an empty match: every module's classes must actually be imported. */
    @ArchTest
    static void everyModuleIsAnalyzed(JavaClasses classes) {
        for (String type : new String[] {
            ROOT + ".domain.combat.Encounter", ROOT + ".application.job.JobService", ROOT + ".adapter.out.content.SqliteContentSource",
            ROOT + ".adapter.json.SimJacksonModule", ROOT + ".adapter.out.report.D1ReportStore", ROOT + ".config.SimConfig"
        }) {
            assertThat(classes.contain(type)).as(type).isTrue();
        }
        assertThat(classes.contain(ROOT + ".testsupport.TestReports")).as("test fixtures are excluded").isFalse();
    }

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage(ROOT + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "javax.persistence..", "com.fasterxml..", "tools.jackson..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainDoesNotDependOnOuterLayers = noClasses()
            .that().resideInAPackage(ROOT + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".application..", ROOT + ".adapter..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage(ROOT + ".application..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule inboundAdaptersDoNotDependOnOutboundAdapters = noClasses()
            .that().resideInAPackage(ROOT + ".adapter.in..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter.out..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule outboundAdaptersDoNotDependOnInboundAdapters = noClasses()
            .that().resideInAPackage(ROOT + ".adapter.out..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter.in..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationIsFrameworkFree = noClasses()
            .that().resideInAPackage(ROOT + ".application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "tools.jackson..", "com.fasterxml..")
            .allowEmptyShould(true);

    // ---- inside the domain: the engine at the bottom, the optimizer at the top ----------------------

    @ArchTest
    static final ArchRule combatDoesNotReachUp = noClasses()
            .that().resideInAPackage(ROOT + ".domain.combat..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + ".domain.content..", ROOT + ".domain.scenario..", ROOT + ".domain.opt..", ROOT + ".domain.ai..");

    /**
     * The engine package keeps each class focused: the combat engine was once one class that used 22 others, and was split
     * into collaborators (see {@code Encounter}). A class that needs more should be split the same way.
     */
    @ArchTest
    static final ArchRule combatClassesStayFocused = classes()
            .that().resideInAPackage(ROOT + ".domain.combat")
            .should(useAtMostProjectClasses(MAX_COLLABORATORS));

    @ArchTest
    static final ArchRule contentDoesNotReachUp = noClasses()
            .that().resideInAPackage(ROOT + ".domain.content..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".domain.scenario..", ROOT + ".domain.opt..");

    @ArchTest
    static final ArchRule scenariosDoNotReachUp = noClasses()
            .that().resideInAPackage(ROOT + ".domain.scenario..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.opt..");

    @ArchTest
    static final ArchRule foundationsStandAlone = noClasses()
            .that().resideInAnyPackage(ROOT + ".domain.core..", ROOT + ".domain.rng..", ROOT + ".domain.dice..", ROOT + ".domain.grid..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + ".domain.combat..", ROOT + ".domain.content..", ROOT + ".domain.scenario..", ROOT + ".domain.opt..",
                    ROOT + ".domain.ai..");

    // ---- no package cycles at any level ------------------------------------------------------------

    @ArchTest
    static final ArchRule domainPackagesAreAcyclic = slices().matching(ROOT + ".domain.(**)").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule applicationPackagesAreAcyclic = slices().matching(ROOT + ".application.(**)").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule webPackagesAreAcyclic = slices().matching(ROOT + ".adapter.in.web.(**)").should().beFreeOfCycles();

    // ---- Spring wiring lives in config -------------------------------------------------------------

    /** Every bean is declared in the composition root, so the classes it wires stay plain and constructible by hand. */
    @ArchTest
    static final ArchRule beansAreDeclaredInConfig = methods()
            .that().areAnnotatedWith(Bean.class)
            .should().beDeclaredInClassesThat().resideInAPackage(ROOT + ".config..");

    @ArchTest
    static final ArchRule configurationClassesLiveInConfig = classes()
            .that().areMetaAnnotatedWith(Configuration.class)
            .should().resideInAPackage(ROOT + ".config..")
            .orShould().haveSimpleName("SimApplication");

    /** Component scanning picks up only the web edge (controllers, advice); everything else is a {@code @Bean}. */
    @ArchTest
    static final ArchRule scannedComponentsAreWebOrConfig = classes()
            .that().areMetaAnnotatedWith(Component.class)
            .should().resideInAnyPackage(ROOT + ".adapter.in.web..", ROOT + ".config..")
            .orShould().haveSimpleName("SimApplication");

    @ArchTest
    static final ArchRule onlyConfigReadsSettings = noClasses()
            .that().resideOutsideOfPackages(ROOT + ".config..")
            .and().doNotHaveSimpleName("SimApplication")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".config..");
}
