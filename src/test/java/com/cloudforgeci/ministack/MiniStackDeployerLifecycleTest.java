package com.cloudforgeci.ministack;

import com.cloudforge.core.local.LocalSameApplicationStackReplacer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniStackDeployerLifecycleTest {

    @Test
    void replaceSameApplicationStacksSkipsBlankApplicationId() {
        try (MiniStackDeployer deployer = new MiniStackDeployer("http://127.0.0.1:1", "us-east-1")) {
            LocalSameApplicationStackReplacer.Result result =
                deployer.replaceSameApplicationStacks(" ", "keep-ministack", Path.of("deployment-contexts"));
            assertTrue(result.deletedStacks().isEmpty());
            assertTrue(result.warning().isEmpty());
        }
    }

    @Test
    void verifyDeploymentFailsWhenStackMissing() {
        try (MiniStackDeployer deployer = new MiniStackDeployer("http://127.0.0.1:1", "us-east-1")) {
            org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> deployer.verifyDeployment("missing-ministack"));
        }
    }

    @Test
    void destroyingAStackRemovesItsHostVolumesAndNothingOutsideTheRoot(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("volumes");
        Path volume = root.resolve("Stack").resolve("joomlaData");
        Files.createDirectories(volume.resolve("nested"));
        Files.writeString(volume.resolve("nested/configuration.php"), "<?php");
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "keep");

        MiniStackDeployer.removeHostVolumes(Map.of(
            "MiniStackHostVolumeJoomlaData", volume.toString(),
            "MiniStackHostVolumeOutside", outside.toString(),
            "ApplicationUrl", "http://example"), root);

        assertFalse(Files.exists(volume), "the stack's volume is removed");
        assertFalse(Files.exists(root.resolve("Stack")), "and its now empty directory");
        assertTrue(Files.exists(outside.resolve("keep.txt")), "a path outside the volume root is never touched");
    }

    @Test
    void aNewStackStartsWithEmptyHostVolumes(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("volumes");
        Path volume = root.resolve("Stack").resolve("joomlaData");
        Files.createDirectories(volume);
        Files.writeString(volume.resolve("configuration.php"), "<?php");

        MiniStackDeployer.emptyHostVolumes(Map.of("MiniStackHostVolumeJoomlaData", volume.toString()), root);

        assertTrue(Files.isDirectory(volume), "the bind mount source still exists");
        try (var files = Files.list(volume)) {
            assertTrue(files.findAny().isEmpty(), "but holds nothing from the earlier stack");
        }
    }
}
