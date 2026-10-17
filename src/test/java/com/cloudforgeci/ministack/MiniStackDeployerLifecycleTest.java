package com.cloudforgeci.ministack;

import com.cloudforge.core.local.LocalSameApplicationStackReplacer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            assertThrows(
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

    @Test
    void aTwelveDigitAccessKeyNamesTheAccountAndAnythingElseIsTheDefault() {
        assertEquals("111111111111", MiniStackCredentials.forAccessKey("111111111111").accessKeyId());
        assertEquals("test", MiniStackCredentials.forAccessKey("test").accessKeyId());
        assertEquals("test", MiniStackCredentials.forAccessKey("11111111111").accessKeyId(), "eleven digits is not an account");
        assertEquals("test", MiniStackCredentials.forAccessKey(null).accessKeyId());
        assertTrue(MiniStackCredentials.isAccountId("222222222222"));
        assertFalse(MiniStackCredentials.isAccountId("2222222222"));
    }

    @Test
    void requestsAreSignedWithTheCredentialsTheCallerGave() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            var credentials = StaticCredentialsProvider.create(
                AwsSessionCredentials.create("ASIAASSUMEDROLE", "secret", "token"));
            try (MiniStackDeployer deployer = new MiniStackDeployer(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "us-east-1", credentials)) {
                assertThrows(RuntimeException.class, () -> deployer.stackExists("anything"));
            }
        } finally {
            server.stop(0);
        }
        assertTrue(authorization.get().contains("Credential=ASIAASSUMEDROLE/"), authorization.get());
    }

    @Test
    void aVolumeDirectoryIsWritableByAnyUser(@TempDir Path temp) throws Exception {
        Path volume = temp.resolve("Stack").resolve("jenkinsHome");
        MiniStackTemplateAdapter.createVolumeDirectory(volume);
        assertEquals("rwxrwxrwx", PosixFilePermissions.toString(Files.getPosixFilePermissions(volume)));
    }
}
