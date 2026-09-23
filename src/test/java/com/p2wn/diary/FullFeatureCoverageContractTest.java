package com.p2wn.diary;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Keeps DiaryKeeper's major protected behavior families tied to concrete regression evidence.
 * This inventory is not a substitute for behavioral assertions.
 */
final class FullFeatureCoverageContractTest {
    @Test
    void majorProtectedFeatureFamiliesRetainRegressionEvidence() {
        Path root = repositoryRoot();
        coverage().forEach((feature, evidence) -> evidence.forEach(path -> assertTrue(
                Files.isRegularFile(root.resolve(path)),
                () -> feature + " lost required regression evidence: " + path
        )));
    }

    private static Map<String, List<String>> coverage() {
        Map<String, List<String>> coverage = new LinkedHashMap<>();
        coverage.put("identity resolution and alias tracking", List.of(
                "src/test/java/com/p2wn/diary/commands/DiaryCommandIdentityTest.java",
                "src/test/java/com/p2wn/diary/data/IdentityVerificationTest.java",
                "src/test/java/com/p2wn/diary/data/PlayerIdentityTest.java",
                "src/test/java/com/p2wn/diary/integrations/floodgate/FloodgateIdentityAdapterTest.java"
        ));
        coverage.put("delivery lifecycle and full-inventory safety", List.of(
                "src/test/java/com/p2wn/diary/commands/DiaryCommandDeliveryTest.java",
                "src/test/java/com/p2wn/diary/data/DiaryStoreDurableReleaseTest.java",
                "src/test/java/com/p2wn/diary/logic/DeliveryServiceFullInventoryIntegrationTest.java",
                "src/test/java/com/p2wn/diary/logic/DeliveryServiceThreadingTest.java"
        ));
        coverage.put("durable diary persistence", List.of(
                "src/test/java/com/p2wn/diary/data/DiaryStoreTest.java",
                "src/test/java/com/p2wn/diary/data/DiaryStoreDurableReleaseTest.java"
        ));
        coverage.put("purge planning, execution and recovery", List.of(
                "src/test/java/com/p2wn/diary/data/PurgeChunkTargetTest.java",
                "src/test/java/com/p2wn/diary/logic/DiaryPurgeServiceTest.java",
                "src/test/java/com/p2wn/diary/logic/DiaryPurgeSystemTest.java"
        ));
        coverage.put("administrative recovery", List.of(
                "src/test/java/com/p2wn/diary/logic/AdminRecoveryServiceTest.java"
        ));
        coverage.put("book text validation", List.of(
                "src/test/java/com/p2wn/diary/util/DiaryTextValidatorTest.java"
        ));
        return Map.copyOf(coverage);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("pom.xml"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("pom.xml"))) {
            return parent;
        }
        throw new IllegalStateException("Could not locate DiaryKeeper repository root from " + current);
    }
}
