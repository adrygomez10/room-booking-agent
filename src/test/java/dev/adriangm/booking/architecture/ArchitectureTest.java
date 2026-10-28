package dev.adriangm.booking.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private final com.tngtech.archunit.core.domain.JavaClasses code = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("dev.adriangm.booking");

    @Test
    void domainDependsOnlyOnItselfAndTheJdk()
    {
        classes()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..domain..")
            .check(code);
    }

    @Test
    void applicationHasNoFrameworkOrAdapterDependencies()
    {
        classes()
            .that()
            .resideInAPackage("..application..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "..application..", "..domain..")
            .check(code);
    }

    @Test
    void bookingDoesNotDependOnTurnCoordination()
    {
        noClasses()
            .that()
            .resideInAPackage("..application.booking..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application.turn..")
            .check(code);
    }

    @Test
    void applicationPackagesHaveNoDependencyCycles()
    {
        slices().matching("dev.adriangm.booking.application.(*)..").should().beFreeOfCycles().check(code);
    }

    @Test
    void adaptersDoNotCallEachOther()
    {
        noClasses()
            .that()
            .resideInAPackage("..web..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..persistence..", "..integration..")
            .check(code);
        noClasses()
            .that()
            .resideInAPackage("..integration..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..persistence..", "..web..")
            .check(code);
        noClasses()
            .that()
            .resideInAPackage("..persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..integration..", "..web..")
            .check(code);
    }

    @Test
    void packagesHaveNoDependencyCycles()
    {
        slices().matching("dev.adriangm.booking.(*)..").should().beFreeOfCycles().check(code);
    }
}
