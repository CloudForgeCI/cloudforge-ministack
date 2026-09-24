package com.cloudforgeci.ministack;

import com.cloudforge.core.local.LocalDeployResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ministack")
@Tag("integration")
class MiniStackDeployerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsStackThroughChangeSetAndReturnsOutputs() throws Exception {
        String stackName = "cfc-deployer-create-" + UUID.randomUUID().toString().substring(0, 8);

        try (MiniStackTestSupport ministack = new MiniStackTestSupport()) {
            ministack.start();
            Path template = writeTemplate(tempDir, "create", minimalVpcTemplate());

            try (MiniStackDeployer deployer = new MiniStackDeployer(ministack.endpoint(), "us-east-1")) {
                LocalDeployResult result = deployer.deploy(stackName, template);

                assertTrue(result.created());
                assertFalse(result.noOp());
                assertTrue(deployer.stackExists(stackName));
                assertFalse(result.changes().isEmpty());
            } finally {
                deleteStack(ministack, stackName);
            }
        }
    }

    @Test
    void updatesExistingStackWithoutRecreatingIt() throws Exception {
        String stackName = "cfc-deployer-update-" + UUID.randomUUID().toString().substring(0, 8);

        try (MiniStackTestSupport ministack = new MiniStackTestSupport()) {
            ministack.start();
            Path initial = writeTemplate(tempDir, "initial", minimalVpcTemplate());
            Path updated = writeTemplate(tempDir, "updated", vpcWithSubnetTemplate());

            try (MiniStackDeployer deployer = new MiniStackDeployer(ministack.endpoint(), "us-east-1")) {
                LocalDeployResult created = deployer.deploy(stackName, initial);
                assertTrue(created.created());

                LocalDeployResult updatedResult = deployer.deploy(stackName, updated);
                assertFalse(updatedResult.created());
                assertFalse(updatedResult.noOp());
                assertTrue(updatedResult.changes().stream()
                    .anyMatch(change -> "AWS::EC2::Subnet".equals(change.resourceType())));
            } finally {
                deleteStack(ministack, stackName);
            }
        }
    }

    @Test
    void treatsIdenticalTemplateAsNoOp() throws Exception {
        String stackName = "cfc-deployer-noop-" + UUID.randomUUID().toString().substring(0, 8);

        try (MiniStackTestSupport ministack = new MiniStackTestSupport()) {
            ministack.start();
            Path template = writeTemplate(tempDir, "noop", minimalVpcTemplate());

            try (MiniStackDeployer deployer = new MiniStackDeployer(ministack.endpoint(), "us-east-1")) {
                LocalDeployResult created = deployer.deploy(stackName, template);
                assertTrue(created.created());

                LocalDeployResult noOp = deployer.deploy(stackName, template);
                assertTrue(noOp.noOp());
                assertFalse(noOp.created());
                assertTrue(noOp.changes().isEmpty());
            } finally {
                deleteStack(ministack, stackName);
            }
        }
    }

    private static Path writeTemplate(Path tempDir, String name, String body) throws Exception {
        Path path = tempDir.resolve(name + ".template.json");
        Files.writeString(path, body);
        return path;
    }

    private static String minimalVpcTemplate() {
        return """
            {
              "AWSTemplateFormatVersion": "2010-09-09",
              "Resources": {
                "Vpc": {
                  "Type": "AWS::EC2::VPC",
                  "Properties": {
                    "CidrBlock": "10.0.0.0/16"
                  }
                }
              }
            }
            """;
    }

    private static String vpcWithSubnetTemplate() {
        return """
            {
              "AWSTemplateFormatVersion": "2010-09-09",
              "Resources": {
                "Vpc": {
                  "Type": "AWS::EC2::VPC",
                  "Properties": {
                    "CidrBlock": "10.0.0.0/16"
                  }
                },
                "SubnetA": {
                  "Type": "AWS::EC2::Subnet",
                  "Properties": {
                    "VpcId": {"Ref": "Vpc"},
                    "CidrBlock": "10.0.1.0/24",
                    "AvailabilityZone": "us-east-1a"
                  }
                }
              }
            }
            """;
    }

    private static void deleteStack(MiniStackTestSupport ministack, String stackName) {
        try (MiniStackDeployer deployer = new MiniStackDeployer(ministack.endpoint(), "us-east-1")) {
            deployer.delete(stackName);
        } catch (Exception ignored) {
            // Preserve the original assertion/deployment failure.
        }
    }
}
