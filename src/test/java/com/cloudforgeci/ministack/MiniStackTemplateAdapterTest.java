package com.cloudforgeci.ministack;

import com.cloudforge.core.local.TemplateAdaptationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniStackTemplateAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void preservesCanonicalTemplateAndReportsOidcAdaptation() throws Exception {
        ObjectNode canonical = templateWithActions("""
            [
              {"Type":"authenticate-oidc","Order":1,"AuthenticateOidcConfig":{"Issuer":"https://idp"}},
              {"Type":"forward","Order":2,"TargetGroupArn":{"Ref":"TargetGroup"}}
            ]
            """);
        String before = MAPPER.writeValueAsString(canonical);

        TemplateAdaptationResult result = MiniStackTemplateAdapter.adapt(canonical);

        assertEquals(before, MAPPER.writeValueAsString(canonical), "canonical AWS template changed");
        assertNotSame(canonical, result.template());
        assertEquals(2, result.adaptations().size());
        assertTrue(result.adaptations().stream()
            .anyMatch(adaptation -> adaptation.path().contains("DefaultActions[0]")));
        assertEquals(
            "forward",
            result.template()
                .path("Resources").path("Listener").path("Properties")
                .path("DefaultActions").get(0).path("Type").asText()
        );
    }

    @Test
    void addsDeterministicLocalUrlOutputWithoutReplacingAwsOutputs() throws Exception {
        ObjectNode canonical = templateWithActions("""
            [{"Type":"forward","TargetGroupArn":{"Ref":"TargetGroup"}}]
            """);
        canonical.withObjectProperty("Outputs").putObject("ApplicationUrl").put("Value", "https://ci.example.com");

        TemplateAdaptationResult result = MiniStackTemplateAdapter.adapt(canonical);

        assertEquals(
            "https://ci.example.com",
            result.template().path("Outputs").path("ApplicationUrl").path("Value").asText()
        );
        assertTrue(result.template().path("Outputs").has("MiniStackLocalUrl"));
        assertFalse(result.template().path("Outputs").has("MiniStackAuthenticatedUrl"));
        assertEquals(1, result.adaptations().size());
    }

    @Test
    void refusesListenerThatWouldHaveNoExecutableAction() throws Exception {
        ObjectNode canonical = templateWithActions("""
            [{"Type":"authenticate-cognito","AuthenticateCognitoConfig":{"UserPoolArn":"pool"}}]
            """);

        assertThrows(IllegalArgumentException.class, () -> MiniStackTemplateAdapter.adapt(canonical));
    }

    @Test
    void replacesEfsWithHostBindMountAndKeepsMountPoints() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {
              "Resources": {
                "Efs": {"Type": "AWS::EFS::FileSystem", "Properties": {}},
                "Task": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "ContainerDefinitions": [{
                      "Name": "app",
                      "Image": "jenkins/jenkins:lts",
                      "MountPoints": [{
                        "SourceVolume": "jenkinsHome",
                        "ContainerPath": "/var/jenkins_home",
                        "ReadOnly": false
                      }]
                    }],
                    "Volumes": [{
                      "Name": "jenkinsHome",
                      "EFSVolumeConfiguration": {
                        "FilesystemId": {"Ref": "Efs"},
                        "TransitEncryption": "ENABLED"
                      }
                    }]
                  }
                }
              }
            }
            """);

        TemplateAdaptationResult result =
            MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "jenkins-mini");

        JsonNode volume = result.template()
            .path("Resources").path("Task").path("Properties")
            .path("Volumes").get(0);
        assertEquals("jenkinsHome", volume.path("Name").asText());
        assertTrue(volume.has("Host"));
        assertTrue(volume.path("Host").path("SourcePath").asText()
            .endsWith("jenkins-mini/jenkinsHome"));

        JsonNode mount = result.template()
            .path("Resources").path("Task").path("Properties")
            .path("ContainerDefinitions").get(0).path("MountPoints").get(0);
        assertEquals("/var/jenkins_home", mount.path("ContainerPath").asText());
        assertEquals("jenkinsHome", mount.path("SourceVolume").asText());

        assertTrue(result.adaptations().stream()
            .anyMatch(adaptation -> adaptation.reason().contains("host bind mount")));
        assertTrue(result.template().path("Outputs").has("MiniStackHostVolumeJenkinsHome"));
    }

    @Test
    void aRetainedFileSystemIsNotListedAmongTheVolumesRemovedOnDestroy() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {"Resources": {
              "Efs": {"Type": "AWS::EFS::FileSystem", "DeletionPolicy": "Retain", "Properties": {}},
              "Task": {"Type": "AWS::ECS::TaskDefinition", "Properties": {
                "ContainerDefinitions": [{"Name": "app", "Image": "x"}],
                "Volumes": [{"Name": "appData", "EFSVolumeConfiguration": {"FilesystemId": {"Ref": "Efs"}}}]}}
            }}""");

        JsonNode outputs = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "retain-mini").template().path("Outputs");

        assertTrue(outputs.has("MiniStackRetainedHostVolumeAppData"));
        assertFalse(outputs.has("MiniStackHostVolumeAppData"), "the deployer removes only MiniStackHostVolume* on destroy");
    }

    @Test
    void removesApplicationAutoScalingResources() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {
              "Resources": {
                "Target": {
                  "Type": "AWS::ApplicationAutoScaling::ScalableTarget",
                  "Properties": {"MaxCapacity": 3, "MinCapacity": 1}
                },
                "Policy": {
                  "Type": "AWS::ApplicationAutoScaling::ScalingPolicy",
                  "Properties": {"PolicyType": "TargetTrackingScaling"}
                },
                "Service": {
                  "Type": "AWS::ECS::Service",
                  "Properties": {"DesiredCount": 1}
                }
              }
            }
            """);

        TemplateAdaptationResult result =
            MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "jenkins-mini");

        assertFalse(result.template().path("Resources").has("Target"));
        assertFalse(result.template().path("Resources").has("Policy"));
        assertTrue(result.template().path("Resources").has("Service"));
        assertEquals(2, result.adaptations().size());
    }

    private static ObjectNode templateWithActions(String actions) throws Exception {
        return (ObjectNode) MAPPER.readTree("""
            {
              "AWSTemplateFormatVersion":"2010-09-09",
              "Resources":{
                "LoadBalancer":{
                  "Type":"AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties":{"Subnets":["subnet-1","subnet-2"]}
                },
                "Listener":{
                  "Type":"AWS::ElasticLoadBalancingV2::Listener",
                  "Properties":{
                    "LoadBalancerArn":{"Ref":"LoadBalancer"},
                    "Port":443,
                    "Protocol":"HTTPS",
                    "DefaultActions":%s
                  }
                }
              }
            }
            """.formatted(actions));
    }

    @Test
    void removesTheComplianceResourcesMiniStackCannotCreateAndKeepsTheOnesItCan() throws Exception {
        ObjectNode template = (ObjectNode) MAPPER.readTree("""
            {"Resources":{
              "Trail":{"Type":"AWS::CloudTrail::Trail","Properties":{}},
              "Detector":{"Type":"AWS::GuardDuty::Detector","Properties":{}},
              "Vault":{"Type":"AWS::Backup::BackupVault","Properties":{}},
              "Plan":{"Type":"AWS::Backup::BackupPlan","Properties":{}},
              "Selection":{"Type":"AWS::Backup::BackupSelection","DependsOn":["Plan"],"Properties":{"BackupPlanId":{"Ref":"Plan"}}},
              "Bucket":{"Type":"AWS::S3::Bucket","DependsOn":["Trail","Vault"],"Properties":{}}}}""");

        TemplateAdaptationResult result = MiniStackTemplateAdapter.adapt(template);

        var resources = result.template().path("Resources");
        assertFalse(resources.has("Trail"));
        assertFalse(resources.has("Detector"));
        assertFalse(resources.has("Selection"));
        assertTrue(resources.has("Vault"));
        assertTrue(resources.has("Plan"));
        assertEquals("Vault", resources.path("Bucket").path("DependsOn").get(0).asText());
        assertEquals(1, resources.path("Bucket").path("DependsOn").size());
    }

    @Test
    void removesACustomResourceThatOnlyRecordsARemovedResource() throws Exception {
        ObjectNode template = (ObjectNode) MAPPER.readTree("""
            {"Resources":{"Trail":{"Type":"AWS::CloudTrail::Trail","Properties":{}},
              "TrailArnWriter":{"Type":"AWS::CloudFormation::CustomResource","Properties":{"Create":{"Fn::GetAtt":["Trail","Arn"]}}}}}""");

        var resources = MiniStackTemplateAdapter.adapt(template).template().path("Resources");

        assertFalse(resources.has("Trail"));
        assertFalse(resources.has("TrailArnWriter"));
    }

    @Test
    void refusesToRemoveAComplianceResourceThatSomethingStillReferences() throws Exception {
        ObjectNode template = (ObjectNode) MAPPER.readTree("""
            {"Resources":{"Trail":{"Type":"AWS::CloudTrail::Trail","Properties":{}},
              "Bucket":{"Type":"AWS::S3::Bucket","Properties":{"Tags":[{"Key":"t","Value":{"Fn::GetAtt":["Trail","Arn"]}}]}}}}""");

        assertThrows(IllegalArgumentException.class, () -> MiniStackTemplateAdapter.adapt(template));
    }

    @Test
    void removesLogRetentionCustomResourcesAndTheirUnusedProvider() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {
              "Resources": {
                "Db": {"Type": "AWS::RDS::DBInstance", "Properties": {"Engine": "mysql"}},
                "DbLogRetentionError": {
                  "Type": "Custom::LogRetention",
                  "Properties": {
                    "LogGroupName": {"Fn::Join": ["", ["/aws/rds/instance/", {"Ref": "Db"}, "/error"]]},
                    "ServiceToken": {"Fn::GetAtt": ["LogRetentionabc123", "Arn"]}
                  }
                },
                "LogRetentionabc123": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {"Role": {"Fn::GetAtt": ["LogRetentionabc123ServiceRole", "Arn"]}},
                  "DependsOn": ["LogRetentionabc123ServiceRoleDefaultPolicy"]
                },
                "LogRetentionabc123ServiceRole": {"Type": "AWS::IAM::Role", "Properties": {}},
                "LogRetentionabc123ServiceRoleDefaultPolicy": {
                  "Type": "AWS::IAM::Policy",
                  "Properties": {"Roles": [{"Ref": "LogRetentionabc123ServiceRole"}]}
                }
              }
            }
            """);

        TemplateAdaptationResult result =
            MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "db-mini");

        JsonNode resources = result.template().path("Resources");
        assertTrue(resources.has("Db"), "the database itself stays");
        assertFalse(resources.has("DbLogRetentionError"));
        assertFalse(resources.has("LogRetentionabc123"));
        assertFalse(resources.has("LogRetentionabc123ServiceRole"));
        assertFalse(resources.has("LogRetentionabc123ServiceRoleDefaultPolicy"));
        assertEquals(4, result.adaptations().size());
    }

    @Test
    void keepsALogRetentionNamedFunctionThatOtherResourcesStillUse() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {
              "Resources": {
                "DbLogRetentionError": {
                  "Type": "Custom::LogRetention",
                  "Properties": {"ServiceToken": {"Fn::GetAtt": ["LogRetentionabc123", "Arn"]}}
                },
                "LogRetentionabc123": {"Type": "AWS::Lambda::Function", "Properties": {}},
                "Other": {
                  "Type": "Custom::Something",
                  "Properties": {"ServiceToken": {"Fn::GetAtt": ["LogRetentionabc123", "Arn"]}}
                }
              }
            }
            """);

        JsonNode resources = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "db-mini").template().path("Resources");

        assertFalse(resources.has("DbLogRetentionError"));
        assertTrue(resources.has("LogRetentionabc123"), "still referenced, so it stays");
        assertTrue(resources.has("Other"));
    }

    @Test
    void replacesThePrivateCertificateChainWithPlainHttpForwarding() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {
              "Resources": {
                "PrivateCA": {"Type": "AWS::ACMPCA::CertificateAuthority", "Properties": {}},
                "RootCert": {"Type": "AWS::ACMPCA::Certificate", "Properties": {}},
                "CaActivation": {"Type": "AWS::ACMPCA::CertificateAuthorityActivation", "Properties": {}},
                "CaPermission": {"Type": "AWS::ACMPCA::Permission", "Properties": {}},
                "HttpsCert": {
                  "Type": "AWS::CertificateManager::Certificate",
                  "Properties": {"CertificateAuthorityArn": {"Ref": "PrivateCA"}},
                  "DependsOn": ["CaPermission"]
                },
                "HttpListener": {
                  "Type": "AWS::ElasticLoadBalancingV2::Listener",
                  "Properties": {"Port": 80, "Protocol": "HTTP", "DefaultActions": [
                    {"Type": "redirect", "RedirectConfig": {"Port": "443", "Protocol": "HTTPS", "StatusCode": "HTTP_301"}}]}
                },
                "HttpsListener": {
                  "Type": "AWS::ElasticLoadBalancingV2::Listener",
                  "Properties": {"Port": 443, "Protocol": "HTTPS",
                    "Certificates": [{"CertificateArn": {"Ref": "HttpsCert"}}],
                    "DefaultActions": [{"Type": "forward", "TargetGroupArn": {"Ref": "AppTargets"}}]}
                },
                "AppTargets": {"Type": "AWS::ElasticLoadBalancingV2::TargetGroup", "Properties": {}},
                "Service": {"Type": "AWS::ECS::Service", "DependsOn": ["HttpsListener", "AppTargets"], "Properties": {}}
              }
            }
            """);

        JsonNode resources = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "oidc-mini").template().path("Resources");

        for (String gone : new String[] {"PrivateCA", "RootCert", "CaActivation", "CaPermission", "HttpsCert", "HttpsListener"}) {
            assertFalse(resources.has(gone), gone);
        }
        JsonNode action = resources.path("HttpListener").path("Properties").path("DefaultActions").get(0);
        assertEquals("forward", action.path("Type").asText());
        assertEquals("AppTargets", action.path("TargetGroupArn").path("Ref").asText());
        assertTrue(resources.has("AppTargets"));
        assertEquals(1, resources.path("Service").path("DependsOn").size(), "the removed listener is no longer a dependency");
        assertEquals("AppTargets", resources.path("Service").path("DependsOn").get(0).asText());
    }

    @Test
    void leavesAnHttpsListenerWithAnImportedCertificateAlone() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {
              "Resources": {
                "Cert": {"Type": "AWS::CertificateManager::Certificate", "Properties": {"DomainName": "app.example.com"}},
                "HttpsListener": {
                  "Type": "AWS::ElasticLoadBalancingV2::Listener",
                  "Properties": {"Port": 443, "Protocol": "HTTPS",
                    "Certificates": [{"CertificateArn": {"Ref": "Cert"}}],
                    "DefaultActions": [{"Type": "forward", "TargetGroupArn": "tg"}]}
                }
              }
            }
            """);

        JsonNode resources = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "domain-mini").template().path("Resources");

        assertTrue(resources.has("Cert"));
        assertTrue(resources.has("HttpsListener"));
    }

    @Test
    void movesCognitoUsersAndMembershipsToMetadataAndDropsBranding() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {
              "Resources": {
                "Pool": {"Type": "AWS::Cognito::UserPool", "Properties": {}},
                "Admins": {"Type": "AWS::Cognito::UserPoolGroup",
                  "Properties": {"GroupName": "Admins", "UserPoolId": {"Ref": "Pool"}}},
                "AdminUser": {"Type": "AWS::Cognito::UserPoolUser", "Properties": {
                  "UserPoolId": {"Ref": "Pool"}, "Username": "admin@example.invalid",
                  "UserAttributes": [{"Name": "email", "Value": "admin@example.invalid"},
                                     {"Name": "email_verified", "Value": "true"}]}},
                "AdminMember": {"Type": "AWS::Cognito::UserPoolUserToGroupAttachment",
                  "DependsOn": ["Admins", "AdminUser"],
                  "Properties": {"UserPoolId": {"Ref": "Pool"}, "Username": "admin@example.invalid", "GroupName": "Admins"}},
                "Branding": {"Type": "AWS::Cognito::ManagedLoginBranding", "Properties": {"UserPoolId": {"Ref": "Pool"}}},
                "Service": {"Type": "AWS::ECS::Service", "DependsOn": ["AdminMember"], "Properties": {}}
              }
            }
            """);

        JsonNode template = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "cog-mini").template();
        JsonNode resources = template.path("Resources");

        assertTrue(resources.has("Pool"));
        assertTrue(resources.has("Admins"), "groups are created by MiniStack's CloudFormation");
        assertFalse(resources.has("AdminUser"));
        assertFalse(resources.has("AdminMember"));
        assertFalse(resources.has("Branding"));
        assertFalse(resources.path("Service").has("DependsOn"), "no dependency on a removed resource");

        JsonNode recorded = template.path("Metadata").path(MiniStackCognitoUsers.METADATA_KEY);
        assertEquals("Pool", recorded.path("users").get(0).path("pool").asText());
        assertEquals("admin@example.invalid", recorded.path("users").get(0).path("username").asText());
        assertEquals("true", recorded.path("users").get(0).path("attributes").path("email_verified").asText());
        assertEquals("Admins", recorded.path("memberships").get(0).path("group").asText());
    }

    @Test
    void addsNoCognitoMetadataWhenTheTemplateHasNoUsers() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree(
            "{\"Resources\":{\"Pool\":{\"Type\":\"AWS::Cognito::UserPool\",\"Properties\":{}}}}");

        JsonNode template = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "cog-mini").template();

        assertFalse(template.path("Metadata").has(MiniStackCognitoUsers.METADATA_KEY));
    }

    @Test
    void theLocalCognitoPasswordIsStableAndMeetsTheUsualPasswordPolicy() {
        String password = MiniStackCognitoUsers.localPassword("Pool", "admin@example.invalid");

        assertEquals(password, MiniStackCognitoUsers.localPassword("Pool", "admin@example.invalid"));
        assertTrue(password.length() >= 12 && password.matches(".*[A-Z].*") && password.matches(".*[a-z].*")
            && password.matches(".*\\d.*") && password.endsWith("!"), password);
    }

    @Test
    void theLoadBalancerForwardsToEcsOnlyOnMiniStack15AndLater() {
        assertFalse(MiniStackTemplateAdapter.albForwardsToEcs("ministackorg/ministack:1.4.9", null));
        assertTrue(MiniStackTemplateAdapter.albForwardsToEcs("ministackorg/ministack:1.5.27", null));
        assertTrue(MiniStackTemplateAdapter.albForwardsToEcs("ministackorg/ministack:2.0.0", null));
        assertFalse(MiniStackTemplateAdapter.albForwardsToEcs("ministackorg/ministack:latest", null));
        assertTrue(MiniStackTemplateAdapter.albForwardsToEcs("ministackorg/ministack:1.4.9", "true"),
            "an emulator started by hand can say it forwards");
        assertFalse(MiniStackTemplateAdapter.albForwardsToEcs("ministackorg/ministack:1.5.27", "false"));
    }

    @Test
    void tellsTheManagerItRunsInALocalEmulatorSoSetupWorksThroughTheLoadBalancer() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {"Resources": {
              "Task": {"Type": "AWS::ECS::TaskDefinition", "Properties": {"ContainerDefinitions": [
                {"Name": "manager", "Image": "cloudforgeci/cloudforge-manager:native-latest",
                 "Environment": [{"Name": "CFC_MANAGER_AUTH_MODE", "Value": "none"}]},
                {"Name": "sidecar", "Image": "cloudforgeci/cloudforge-synth-service:latest", "Environment": []}]}},
              "Other": {"Type": "AWS::ECS::TaskDefinition", "Properties": {"ContainerDefinitions": [
                {"Name": "app", "Image": "gitea/gitea:latest", "Environment": []}]}}}}
            """);

        JsonNode resources = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "mgr-mini").template().path("Resources");

        JsonNode manager = resources.path("Task").path("Properties").path("ContainerDefinitions").get(0);
        Map<String, String> env = new HashMap<>();
        manager.path("Environment").forEach(e -> env.put(e.path("Name").asText(), e.path("Value").asText()));
        assertEquals("ministack", env.get("CFC_MANAGER_TARGET"));
        assertEquals("http://cfc-ministack:4566", env.get("CFC_MANAGER_MINISTACK_ENDPOINT"));
        assertEquals("http://host.docker.internal:4570", env.get("CFC_MANAGER_MINISTACK_AGENT_URL"),
            "the Manager is told where the agent is");
        assertEquals("none", env.get("CFC_MANAGER_AUTH_MODE"), "existing settings are kept");
        assertEquals(0, resources.path("Task").path("Properties").path("ContainerDefinitions").get(1)
            .path("Environment").size(), "the sidecar is not the Manager");
        assertEquals(0, resources.path("Other").path("Properties").path("ContainerDefinitions").get(0)
            .path("Environment").size(), "other applications are untouched");
    }

    @Test
    void theEmulatorPublishesTheLegacyDatabasePortRangeOnlyBefore15() {
        assertFalse(MiniStackTemplateAdapter.isVersion15OrLater("ministackorg/ministack:1.4.9"));
        assertTrue(MiniStackTemplateAdapter.isVersion15OrLater("ministackorg/ministack:1.5.27"));
        assertFalse(MiniStackTemplateAdapter.isVersion15OrLater("ministackorg/ministack:latest"));
        assertFalse(MiniStackTemplateAdapter.isVersion15OrLater(null));
    }

    @Test
    void aSubdomainRecordIsRemovedAndGivesTheTaskItsOwnEdgeHostname() throws Exception {
        ObjectNode template = (ObjectNode) MAPPER.readTree("""
            {"Resources": {
              "Record": {"Type": "AWS::Route53::RecordSet",
                "Properties": {"Name": "jenkins2.example.com.", "HostedZoneId": "/hostedzone/LOCALEMULATORZONE"}},
              "Task": {"Type": "AWS::ECS::TaskDefinition", "DependsOn": ["Record"],
                "Properties": {"ContainerDefinitions": [
                  {"Name": "app", "Environment": [{"Name": "A", "Value": "1"}]},
                  {"Name": "sidecar"}]}}
            }}""");

        ObjectNode adapted = MiniStackTemplateAdapter.adapt(template).template();

        assertFalse(adapted.path("Resources").has("Record"), "the record cannot be created on MiniStack");
        assertFalse(adapted.path("Resources").path("Task").has("DependsOn"));
        for (JsonNode container : adapted.path("Resources").path("Task").path("Properties").path("ContainerDefinitions")) {
            Map<String, String> env = new HashMap<>();
            container.path("Environment").forEach(e -> env.put(e.path("Name").asText(), e.path("Value").asText()));
            assertEquals("jenkins2.cloudforge.localhost", env.get("CFC_LOCALSTACK_EDGE_HOSTNAME"),
                container.path("Name").asText());
        }
    }

    @Test
    void aTemplateWithoutARecordKeepsItsContainersUntouched() throws Exception {
        ObjectNode template = (ObjectNode) MAPPER.readTree("""
            {"Resources": {"Task": {"Type": "AWS::ECS::TaskDefinition",
              "Properties": {"ContainerDefinitions": [{"Name": "app"}]}}}}""");

        ObjectNode adapted = MiniStackTemplateAdapter.adapt(template).template();

        assertFalse(adapted.toString().contains("CFC_LOCALSTACK_EDGE_HOSTNAME"));
    }

    @Test
    void edgeHostnameKeepsOnlyTheFirstLabel() {
        assertEquals("jenkins1.cloudforge.localhost", MiniStackTemplateAdapter.edgeHostname("jenkins1.example.com."));
        assertEquals("app.cloudforge.localhost", MiniStackTemplateAdapter.edgeHostname("App.Example.Org"));
        assertEquals(null, MiniStackTemplateAdapter.edgeHostname(" "));
        assertEquals(null, MiniStackTemplateAdapter.edgeHostname(null));
    }

    private static Map<String, String> environmentOf(JsonNode container) {
        Map<String, String> env = new HashMap<>();
        container.path("Environment").forEach(e -> env.put(e.path("Name").asText(), e.path("Value").asText()));
        return env;
    }

    @Test
    void pointsTheManagerAtItsSynthSidecarThroughAStackScopedAlias() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {"Resources": {
              "Task": {"Type": "AWS::ECS::TaskDefinition", "Properties": {"ContainerDefinitions": [
                {"Name": "manager", "Image": "cloudforgeci/cloudforge-manager:native-latest",
                 "Environment": [{"Name": "CFC_MANAGER_AUTH_MODE", "Value": "none"}]},
                {"Name": "cloudforge-synth-service", "Image": "cloudforgeci/cloudforge-synth-service:latest",
                 "PortMappings": [{"ContainerPort": 8091}], "Environment": []}]}}}}
            """);

        TemplateAdaptationResult result = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "Mgr_Test.1");

        JsonNode containers = result.template().path("Resources").path("Task").path("Properties")
            .path("ContainerDefinitions");
        assertEquals("http://synth-mgr-test-1:8091", environmentOf(containers.get(0)).get("SYNTH_SERVICE_URL"));
        assertEquals("none", environmentOf(containers.get(0)).get("CFC_MANAGER_AUTH_MODE"), "existing settings are kept");
        assertFalse(environmentOf(containers.get(1)).containsKey("SYNTH_SERVICE_URL"), "the sidecar is not rewritten");

        JsonNode recorded = result.template().path("Metadata").path(MiniStackSidecarAliases.METADATA_KEY);
        assertEquals(1, recorded.size());
        assertEquals("cloudforge-synth-service", recorded.get(0).path("container").asText());
        assertEquals("synth-mgr-test-1", recorded.get(0).path("alias").asText());
        assertEquals("Task", recorded.get(0).path("taskDefinition").asText());
        assertTrue(result.adaptations().stream().anyMatch(a -> a.reason().contains("synth sidecar")));
        assertFalse(canonical.has("Metadata"), "the canonical template is not changed");
    }

    @Test
    void leavesATaskWithoutTheSynthSidecarAndOtherApplicationsAlone() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {"Resources": {
              "ManagerOnly": {"Type": "AWS::ECS::TaskDefinition", "Properties": {"ContainerDefinitions": [
                {"Name": "manager", "Image": "cloudforgeci/cloudforge-manager:native-latest",
                 "Environment": [{"Name": "CFC_MANAGER_PORT", "Value": "1958"}]}]}},
              "Other": {"Type": "AWS::ECS::TaskDefinition", "Properties": {"ContainerDefinitions": [
                {"Name": "app", "Image": "gitea/gitea:latest", "Environment": []},
                {"Name": "cloudforge-synth-service", "Image": "x", "Environment": []}]}}}}
            """);

        TemplateAdaptationResult result = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "mgr");

        JsonNode resources = result.template().path("Resources");
        assertFalse(environmentOf(resources.path("ManagerOnly").path("Properties").path("ContainerDefinitions").get(0))
            .containsKey("SYNTH_SERVICE_URL"));
        assertEquals(0, resources.path("Other").path("Properties").path("ContainerDefinitions").get(0)
            .path("Environment").size());
        assertTrue(result.template().path("Metadata").path(MiniStackSidecarAliases.METADATA_KEY).isMissingNode());
    }

    @Test
    void sidecarAliasIsAValidDnsLabelUniquePerStack() {
        assertEquals("synth-mgr-t-nocognito", MiniStackSidecarAliases.aliasFor("mgr-t-nocognito"));
        assertEquals("synth-local", MiniStackSidecarAliases.aliasFor(null));
        assertEquals("synth-local", MiniStackSidecarAliases.aliasFor("  "));
        assertEquals("synth-local", MiniStackSidecarAliases.aliasFor("___"));
        assertNotEquals(MiniStackSidecarAliases.aliasFor("mgr-a"), MiniStackSidecarAliases.aliasFor("mgr-b"));
        String longAlias = MiniStackSidecarAliases.aliasFor("m".repeat(100));
        assertTrue(longAlias.length() <= 63);
        assertTrue(longAlias.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?"));
    }

    @Test
    void selectsTheSidecarContainerOfThisStackAtTheDeployedRevisionOnly() {
        String listing = "ministack-ecs-aaa-cloudforge-synth-service\tarn:aws:ecs:us-east-1:000000000000:cluster/other-ministack-Cluster1\t4\n"
            + "ministack-ecs-old-cloudforge-synth-service\tarn:aws:ecs:us-east-1:000000000000:cluster/mgr-ministack-Cluster2\t2\n"
            + "ministack-ecs-new-cloudforge-synth-service\tarn:aws:ecs:us-east-1:000000000000:cluster/mgr-ministack-Cluster2\t3\n";
        assertEquals("ministack-ecs-new-cloudforge-synth-service",
            MiniStackSidecarAliases.selectContainer(listing, "mgr-ministack", "3"), "an update is still rolling out");
        assertEquals("ministack-ecs-old-cloudforge-synth-service",
            MiniStackSidecarAliases.selectContainer(listing, "mgr-ministack", "2"));
        assertEquals(null, MiniStackSidecarAliases.selectContainer(listing, "mgr-ministack", "9"),
            "no container runs the deployed revision yet");
        assertEquals("ministack-ecs-old-cloudforge-synth-service",
            MiniStackSidecarAliases.selectContainer(listing, "mgr-ministack", null), "an unknown revision accepts any");
        assertEquals(null, MiniStackSidecarAliases.selectContainer(listing, "absent-ministack", "3"));
        assertEquals(null, MiniStackSidecarAliases.selectContainer("", "mgr-ministack", "3"));
        assertEquals(null, MiniStackSidecarAliases.selectContainer(null, "mgr-ministack", "3"));
        assertEquals(null, MiniStackSidecarAliases.selectContainer("no-tab-line", "mgr-ministack", null));
        assertEquals(null, MiniStackSidecarAliases.selectContainer(
            "c\tarn:aws:ecs:r:0:cluster/mgr-ministack-C", "mgr-ministack", "3"), "a container with no revision label");
    }

    private static ObjectNode customResourceCalling(String action, String service) {
        ObjectNode resource = MAPPER.createObjectNode().put("Type", "Custom::AWS");
        resource.putObject("Properties").put("Create",
            "{\"action\":\"" + action + "\",\"service\":\"" + service + "\"}");
        return resource;
    }

    @Test
    void removesTheReadOnlyVerificationsThatNeverCompleteOnMiniStack() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        resources.set("Verify", customResourceCalling("batchGetAccountStatus", "Inspector2"));
        resources.set("VerifyHub", customResourceCalling("describeHub", "SecurityHub"));
        resources.set("Other", customResourceCalling("putParameter", "SSM"));
        resources.putObject("VerifyPolicy").put("Type", "AWS::IAM::Policy");

        TemplateAdaptationResult result = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "stg");

        JsonNode adapted = result.template().path("Resources");
        assertFalse(adapted.has("Verify"), "the Inspector check is removed");
        assertFalse(adapted.has("VerifyHub"), "any read-only check is removed, not only Inspector's");
        assertTrue(adapted.has("VerifyPolicy"));
        assertTrue(adapted.has("Other"), "a custom resource that writes is kept");
        assertTrue(result.adaptations().stream().anyMatch(a -> a.path().equals("Resources.Verify")));
    }

    private static ObjectNode mysqlTemplate(String version, String family) throws Exception {
        return (ObjectNode) MAPPER.readTree("""
            {"Resources": {
              "Db": {"Type": "AWS::RDS::DBInstance", "Properties": {"Engine": "mysql", "EngineVersion": "%s"}},
              "Pg": {"Type": "AWS::RDS::DBParameterGroup", "Properties": {"Family": "%s"}},
              "Maria": {"Type": "AWS::RDS::DBInstance", "Properties": {"Engine": "mariadb", "EngineVersion": "5.7"}},
              "Postgres": {"Type": "AWS::RDS::DBInstance", "Properties": {"Engine": "postgres", "EngineVersion": "14"}}}}
            """.formatted(version, family));
    }

    @Test
    void runsMySql80WhereTheHostCannotPullMySql57() throws Exception {
        ObjectNode template = mysqlTemplate("5.7", "mysql5.7");
        java.util.List<com.cloudforge.core.local.TemplateAdaptation> adaptations = new java.util.ArrayList<>();

        MiniStackTemplateAdapter.substituteMySql57(template, adaptations, true);

        JsonNode resources = template.path("Resources");
        assertEquals("8.0", resources.path("Db").path("Properties").path("EngineVersion").asText());
        assertEquals("mysql8.0", resources.path("Pg").path("Properties").path("Family").asText());
        assertEquals("5.7", resources.path("Maria").path("Properties").path("EngineVersion").asText(),
            "another engine's 5.7 is not MySQL");
        assertEquals("14", resources.path("Postgres").path("Properties").path("EngineVersion").asText());
        assertEquals(2, adaptations.size());
        assertEquals("\"5.7\"", adaptations.get(0).original().toString(), "the original value is reported");
    }

    @Test
    void keepsMySql57AndOtherVersionsWhenTheHostCanRunIt() throws Exception {
        ObjectNode native57 = mysqlTemplate("5.7", "mysql5.7");
        java.util.List<com.cloudforge.core.local.TemplateAdaptation> none = new java.util.ArrayList<>();
        MiniStackTemplateAdapter.substituteMySql57(native57, none, false);
        assertEquals("5.7", native57.path("Resources").path("Db").path("Properties").path("EngineVersion").asText());
        assertEquals("mysql5.7", native57.path("Resources").path("Pg").path("Properties").path("Family").asText());
        assertTrue(none.isEmpty());

        ObjectNode eight = mysqlTemplate("8.4", "mysql8.4");
        MiniStackTemplateAdapter.substituteMySql57(eight, none, true);
        assertEquals("8.4", eight.path("Resources").path("Db").path("Properties").path("EngineVersion").asText());
        assertEquals("mysql8.4", eight.path("Resources").path("Pg").path("Properties").path("Family").asText());
        assertTrue(none.isEmpty());
    }

    @Test
    void substitutesAPatchLevelMySql57Too() throws Exception {
        ObjectNode template = mysqlTemplate("5.7.44", "mysql5.7");
        MiniStackTemplateAdapter.substituteMySql57(template, new java.util.ArrayList<>(), true);
        assertEquals("8.0", template.path("Resources").path("Db").path("Properties").path("EngineVersion").asText());
    }
}
