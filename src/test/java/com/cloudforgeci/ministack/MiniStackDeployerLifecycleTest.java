package com.cloudforgeci.ministack;

import com.cloudforge.core.local.LocalSameApplicationStackReplacer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

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
}
