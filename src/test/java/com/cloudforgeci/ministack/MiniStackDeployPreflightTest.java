package com.cloudforgeci.ministack;

import com.cloudforge.core.config.DeploymentConfig;
import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalHostPortConflictChecker;
import com.cloudforge.core.local.LocalHostPortOccupant;
import com.cloudforge.core.local.PreflightMode;
import com.cloudforge.core.local.PreflightResult;
import com.cloudforge.core.local.PreflightViolation;
import com.cloudforge.core.local.TemplateResourceScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniStackDeployPreflightTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void blocksWhenProvisionDatabaseEnabled() throws Exception {
        DeploymentConfig config = new DeploymentConfig();
        config.provisionDatabase = true;

        PreflightResult result = MiniStackDeployPreflight.validate(config, null, null);

        assertFalse(result.allowed(PreflightMode.ENFORCE));
        assertEquals("RDS_REQUIRED", result.blockingViolations().getFirst().ruleId());
    }

    @Test
    void blocksUnsupportedCfnTypesInTemplate() throws Exception {
        ObjectNode template = MAPPER.createObjectNode();
        template.putObject("Resources")
            .putObject("DbParams")
            .put("Type", "AWS::RDS::DBParameterGroup");
        Path canonical = tempDir.resolve("mattermost.json");
        Files.writeString(canonical, MAPPER.writeValueAsString(template));

        PreflightResult result = MiniStackDeployPreflight.validate(new DeploymentConfig(), null, canonical);

        assertFalse(result.allowed(PreflightMode.ENFORCE));
        assertEquals("UNSUPPORTED_CFN_TYPES", result.blockingViolations().getFirst().ruleId());
    }

    @Test
    void allowsJenkinsLikeTemplate() throws Exception {
        ObjectNode template = MAPPER.createObjectNode();
        ObjectNode resources = template.putObject("Resources");
        resources.putObject("Alb").put("Type", "AWS::ElasticLoadBalancingV2::LoadBalancer");
        resources.putObject("Task").put("Type", "AWS::ECS::TaskDefinition");
        Path canonical = tempDir.resolve("jenkins.json");
        Files.writeString(canonical, MAPPER.writeValueAsString(template));

        PreflightResult result = MiniStackDeployPreflight.validate(new DeploymentConfig(), null, canonical);

        assertTrue(result.allowed(PreflightMode.ENFORCE));
        assertEquals(DeploymentTarget.MINISTACK, result.target());
    }

    @Test
    void blocksWhenPortAlreadyClaimedByAnotherStack() throws Exception {
        ObjectNode template = MAPPER.createObjectNode();
        ObjectNode service = template.putObject("Resources").putObject("Service");
        service.put("Type", "AWS::ECS::Service");
        service.putObject("Properties")
            .putArray("LoadBalancers")
            .addObject()
            .put("ContainerPort", 3000);
        Path canonical = tempDir.resolve("gitea.json");
        Files.writeString(canonical, MAPPER.writeValueAsString(template));

        List<PreflightViolation> violations = new ArrayList<>();
        LocalHostPortConflictChecker.validateAgainstOccupants(
            DeploymentTarget.MINISTACK,
            "Gitea-Stack-ministack",
            null,
            TemplateResourceScanner.readTemplate(canonical),
            List.of(new LocalHostPortOccupant(
                "Grafana-Stack-ministack",
                3000,
                "stack output MiniStackApplicationUrl")),
            violations);
        PreflightResult result = PreflightResult.of(DeploymentTarget.MINISTACK, violations);

        assertFalse(result.allowed(PreflightMode.ENFORCE));
        assertEquals("HOST_PORT_CONFLICT", result.blockingViolations().getFirst().ruleId());
    }

    @Test
    void allowsRedeployOfSameStackOnSamePort() throws Exception {
        ObjectNode template = MAPPER.createObjectNode();
        ObjectNode service = template.putObject("Resources").putObject("Service");
        service.put("Type", "AWS::ECS::Service");
        service.putObject("Properties")
            .putArray("LoadBalancers")
            .addObject()
            .put("ContainerPort", 3000);
        Path canonical = tempDir.resolve("grafana.json");
        Files.writeString(canonical, MAPPER.writeValueAsString(template));

        List<PreflightViolation> violations = new ArrayList<>();
        LocalHostPortConflictChecker.validateAgainstOccupants(
            DeploymentTarget.MINISTACK,
            "Grafana-Stack-ministack",
            null,
            TemplateResourceScanner.readTemplate(canonical),
            List.of(new LocalHostPortOccupant(
                "Grafana-Stack-ministack",
                3000,
                "stack output MiniStackApplicationUrl")),
            violations);
        PreflightResult result = PreflightResult.of(DeploymentTarget.MINISTACK, violations);

        assertTrue(result.allowed(PreflightMode.ENFORCE));
    }

    @Test
    void enforceModeThrowsFormattedMessage() throws Exception {
        DeploymentConfig config = new DeploymentConfig();
        config.provisionDatabase = true;
        PreflightResult result = MiniStackDeployPreflight.validate(config, null, null);

        java.io.IOException blocked = assertThrows(
            java.io.IOException.class,
            () -> result.throwIfBlocked(PreflightMode.ENFORCE));
        assertTrue(blocked.getMessage().contains("RDS"));
        assertTrue(blocked.getMessage().contains("option 8"));
    }
}
