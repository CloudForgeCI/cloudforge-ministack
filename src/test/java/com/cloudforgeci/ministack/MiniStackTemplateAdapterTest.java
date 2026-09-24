package com.cloudforgeci.ministack;

import com.cloudforge.core.local.TemplateAdaptationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
