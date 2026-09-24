package com.cloudforgeci.ministack;

import com.cloudforge.core.local.TemplateAdaptationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.ListStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.StackResourceSummary;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live MiniStack API assertions for security-group <em>rule records</em>,
 * Route53 alias → ALB wiring, ALB/listener inventory, and ACM presence.
 *
 * <p>Does <strong>not</strong> claim packet-filter enforcement or ALB→ECS forward
 * (MiniStack adapts forwards to localhost redirect when an ECS service is present).</p>
 */
@Tag("ministack")
@Tag("integration")
class MiniStackNativeNetworkVerificationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ZONE_NAME = "native.example.test.";
    private static final String FQDN = "app.native.example.test.";

    @Test
    void verifiesSecurityGroupRulesDomainAlbAndTlsInventory() throws Exception {
        String stackName = "cfc-native-net-" + UUID.randomUUID().toString().substring(0, 8);
        String lbName = "cfc-n-" + UUID.randomUUID().toString().substring(0, 8);

        try (MiniStackTestSupport ministack = new MiniStackTestSupport()) {
            ministack.start();
            try {
                ObjectNode canonical = networkTemplate(lbName);
                TemplateAdaptationResult adapted = MiniStackTemplateAdapter.INSTANCE.adapt(canonical, stackName);
                assertTrue(adapted.template().path("Outputs").has("MiniStackLocalUrl"));
                // Standalone SecurityGroupIngress resources must be inlined for MiniStack CFN.
                assertFalse(hasResourceType(adapted.template(), "AWS::EC2::SecurityGroupIngress"),
                    "adapter must inline SecurityGroupIngress onto parent SG");
                assertTrue(
                    adapted.adaptations().stream().anyMatch(a ->
                        a.path().contains("AppIngressFromAlb")
                            || a.reason().toLowerCase(Locale.ROOT).contains("ingress")),
                    () -> "expected ingress-inline adaptation: " + adapted.adaptations());

                create(ministack, stackName, adapted.template());

                Map<String, String> physical = physicalIds(ministack, stackName);
                String albSgId = physical.get("AlbSecurityGroup");
                String appSgId = physical.get("AppSecurityGroup");
                String lbId = physical.get("LoadBalancer");
                assertNotNull(albSgId, "AlbSecurityGroup physical id");
                assertNotNull(appSgId, "AppSecurityGroup physical id");
                assertNotNull(lbId, "LoadBalancer physical id");

                SecurityGroup albSg = describeSg(ministack, albSgId);
                assertTrue(hasCidrIngress(albSg, 80, "0.0.0.0/0"),
                    "ALB SG should record HTTP:80 from 0.0.0.0/0: " + albSg.ipPermissions());
                assertTrue(hasCidrIngress(albSg, 443, "0.0.0.0/0"),
                    "ALB SG should record HTTPS:443 from 0.0.0.0/0: " + albSg.ipPermissions());

                SecurityGroup appSg = describeSg(ministack, appSgId);
                // MiniStack records the inlined port; peer UserIdGroupPairs may stay empty
                // after Ref resolution — assert peer wiring on the adapted template instead.
                assertTrue(hasAnyIngressPort(appSg, 8080),
                    "App SG should record port 8080 after ingress inline: " + appSg.ipPermissions());
                assertTrue(
                    adaptedTemplateHasPeerIngress(
                        adapted.template(), "AppSecurityGroup", 8080, "AlbSecurityGroup"),
                    "adapted AppSecurityGroup must keep SourceSecurityGroupId → AlbSecurityGroup");
                // Negative metadata: template never opened 5432 — inventory must not invent it.
                assertFalse(hasAnyIngressPort(appSg, 5432),
                    "App SG must not record port 5432: " + appSg.ipPermissions());

                LoadBalancer lb = ministack.elb().describeLoadBalancers(
                        DescribeLoadBalancersRequest.builder().loadBalancerArns(lbId).build())
                    .loadBalancers().getFirst();
                assertEquals(lbName, lb.loadBalancerName());
                assertTrue(lb.securityGroups().contains(albSgId),
                    "ALB must be associated with AlbSecurityGroup");
                assertNotNull(lb.dnsName());
                assertFalse(lb.dnsName().isBlank());

                List<Listener> listeners = ministack.elb().describeListeners(
                        DescribeListenersRequest.builder().loadBalancerArn(lb.loadBalancerArn()).build())
                    .listeners();
                assertTrue(listeners.stream().anyMatch(l -> l.port() != null && l.port() == 80),
                    "HTTP listener on 80 required");
                assertTrue(listeners.stream().anyMatch(l -> l.port() != null && l.port() == 443),
                    "HTTPS listener on 443 required when TLS resources present");
                Listener http = listeners.stream()
                    .filter(l -> l.port() != null && l.port() == 80)
                    .findFirst().orElseThrow();
                assertFalse(http.defaultActions().isEmpty());
                assertEquals("fixed-response", http.defaultActions().getFirst().typeAsString());

                String zoneId = ministack.route53().listHostedZones().hostedZones().stream()
                    .filter(z -> ZONE_NAME.equals(z.name()))
                    .findFirst().orElseThrow(() -> new AssertionError("hosted zone missing: " + ZONE_NAME))
                    .id();
                ResourceRecordSet alias = ministack.route53()
                    .listResourceRecordSets(b -> b.hostedZoneId(zoneId))
                    .resourceRecordSets().stream()
                    .filter(r -> FQDN.equals(r.name()))
                    .filter(r -> r.type() == RRType.A || "A".equalsIgnoreCase(r.typeAsString()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("alias A record missing for " + FQDN));
                assertNotNull(alias.aliasTarget(), "record must be alias to ALB, not CNAME");
                String aliasDns = alias.aliasTarget().dnsName();
                assertNotNull(aliasDns);
                assertTrue(
                    normalizeDns(aliasDns).equals(normalizeDns(lb.dnsName()))
                        || normalizeDns(aliasDns).startsWith(normalizeDns(lb.dnsName())),
                    () -> "alias DNS " + aliasDns + " should target ALB " + lb.dnsName());

                assertFalse(ministack.acm().listCertificates().certificateSummaryList().isEmpty(),
                    "ACM should list at least one certificate after TLS resources deploy");

                assertReachable(output(ministack, stackName, "MiniStackLocalUrl"));
            } finally {
                deleteQuietly(ministack, stackName);
            }
        }
    }

    @Test
    void adaptedListenerRedirectsEcsForwardToLocalhostPort() throws Exception {
        ObjectNode canonical = (ObjectNode) MAPPER.readTree("""
            {
              "AWSTemplateFormatVersion":"2010-09-09",
              "Resources":{
                "Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.50.0.0/16"}},
                "SubnetA":{"Type":"AWS::EC2::Subnet","Properties":{
                  "VpcId":{"Ref":"Vpc"},"CidrBlock":"10.50.1.0/24","AvailabilityZone":"us-east-1a"
                }},
                "SubnetB":{"Type":"AWS::EC2::Subnet","Properties":{
                  "VpcId":{"Ref":"Vpc"},"CidrBlock":"10.50.2.0/24","AvailabilityZone":"us-east-1b"
                }},
                "AlbSg":{"Type":"AWS::EC2::SecurityGroup","Properties":{
                  "GroupDescription":"alb","VpcId":{"Ref":"Vpc"}
                }},
                "TargetGroup":{"Type":"AWS::ElasticLoadBalancingV2::TargetGroup","Properties":{
                  "Port":8080,"Protocol":"HTTP","VpcId":{"Ref":"Vpc"},"TargetType":"ip"
                }},
                "LoadBalancer":{"Type":"AWS::ElasticLoadBalancingV2::LoadBalancer","Properties":{
                  "Name":"cfc-redir","Scheme":"internet-facing","Type":"application",
                  "Subnets":[{"Ref":"SubnetA"},{"Ref":"SubnetB"}],
                  "SecurityGroups":[{"Ref":"AlbSg"}]
                }},
                "Listener":{"Type":"AWS::ElasticLoadBalancingV2::Listener","Properties":{
                  "LoadBalancerArn":{"Ref":"LoadBalancer"},"Port":80,"Protocol":"HTTP",
                  "DefaultActions":[{"Type":"forward","TargetGroupArn":{"Ref":"TargetGroup"}}]
                }},
                "Cluster":{"Type":"AWS::ECS::Cluster","Properties":{"ClusterName":"cfc-redir"}},
                "Service":{"Type":"AWS::ECS::Service","Properties":{
                  "Cluster":{"Ref":"Cluster"},
                  "DesiredCount":0,
                  "LoadBalancers":[{
                    "ContainerName":"app","ContainerPort":8080,
                    "TargetGroupArn":{"Ref":"TargetGroup"}
                  }]
                }}
              }
            }
            """);

        TemplateAdaptationResult result =
            MiniStackTemplateAdapter.INSTANCE.adapt(canonical, "cfc-redir-assert");
        JsonNode actions = result.template()
            .path("Resources").path("Listener").path("Properties").path("DefaultActions");
        assertTrue(actions.isArray() && !actions.isEmpty());
        JsonNode action = actions.get(0);
        assertEquals("redirect", action.path("Type").asText());
        assertEquals("localhost", action.path("RedirectConfig").path("Host").asText());
        assertEquals("8080", action.path("RedirectConfig").path("Port").asText());
        assertTrue(result.adaptations().stream()
            .anyMatch(a -> a.reason().contains("cannot forward to ECS")));
    }

    private static ObjectNode networkTemplate(String lbName) throws Exception {
        return (ObjectNode) MAPPER.readTree("""
            {
              "AWSTemplateFormatVersion":"2010-09-09",
              "Resources":{
                "Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.51.0.0/16"}},
                "SubnetA":{"Type":"AWS::EC2::Subnet","Properties":{
                  "VpcId":{"Ref":"Vpc"},"CidrBlock":"10.51.1.0/24","AvailabilityZone":"us-east-1a"
                }},
                "SubnetB":{"Type":"AWS::EC2::Subnet","Properties":{
                  "VpcId":{"Ref":"Vpc"},"CidrBlock":"10.51.2.0/24","AvailabilityZone":"us-east-1b"
                }},
                "AlbSecurityGroup":{"Type":"AWS::EC2::SecurityGroup","Properties":{
                  "GroupDescription":"MiniStack native ALB","VpcId":{"Ref":"Vpc"},
                  "SecurityGroupIngress":[
                    {"IpProtocol":"tcp","FromPort":80,"ToPort":80,"CidrIp":"0.0.0.0/0"},
                    {"IpProtocol":"tcp","FromPort":443,"ToPort":443,"CidrIp":"0.0.0.0/0"}
                  ]
                }},
                "AppSecurityGroup":{"Type":"AWS::EC2::SecurityGroup","Properties":{
                  "GroupDescription":"MiniStack native app","VpcId":{"Ref":"Vpc"}
                }},
                "AppIngressFromAlb":{"Type":"AWS::EC2::SecurityGroupIngress","Properties":{
                  "GroupId":{"Ref":"AppSecurityGroup"},
                  "IpProtocol":"tcp","FromPort":8080,"ToPort":8080,
                  "SourceSecurityGroupId":{"Ref":"AlbSecurityGroup"}
                }},
                "TargetGroup":{"Type":"AWS::ElasticLoadBalancingV2::TargetGroup","Properties":{
                  "Name":"cfc-native-tg","Port":8080,"Protocol":"HTTP",
                  "VpcId":{"Ref":"Vpc"},"TargetType":"ip"
                }},
                "LoadBalancer":{"Type":"AWS::ElasticLoadBalancingV2::LoadBalancer","Properties":{
                  "Name":"%s","Scheme":"internet-facing","Type":"application",
                  "Subnets":[{"Ref":"SubnetA"},{"Ref":"SubnetB"}],
                  "SecurityGroups":[{"Ref":"AlbSecurityGroup"}]
                }},
                "HttpListener":{"Type":"AWS::ElasticLoadBalancingV2::Listener","Properties":{
                  "LoadBalancerArn":{"Ref":"LoadBalancer"},"Port":80,"Protocol":"HTTP",
                  "DefaultActions":[{"Type":"fixed-response","FixedResponseConfig":{
                    "StatusCode":"200","ContentType":"text/plain","MessageBody":"cfc-ministack"
                  }}]
                }},
                "Certificate":{"Type":"AWS::CertificateManager::Certificate","Properties":{
                  "DomainName":"app.native.example.test","ValidationMethod":"DNS"
                }},
                "HttpsListener":{"Type":"AWS::ElasticLoadBalancingV2::Listener","Properties":{
                  "LoadBalancerArn":{"Ref":"LoadBalancer"},"Port":443,"Protocol":"HTTPS",
                  "Certificates":[{"CertificateArn":{"Ref":"Certificate"}}],
                  "DefaultActions":[{"Type":"fixed-response","FixedResponseConfig":{
                    "StatusCode":"200","ContentType":"text/plain","MessageBody":"cfc-ministack"
                  }}]
                }},
                "HostedZone":{"Type":"AWS::Route53::HostedZone","Properties":{"Name":"%s"}},
                "AliasRecord":{"Type":"AWS::Route53::RecordSet","Properties":{
                  "HostedZoneId":{"Ref":"HostedZone"},
                  "Name":"%s",
                  "Type":"A",
                  "AliasTarget":{
                    "DNSName":{"Fn::GetAtt":["LoadBalancer","DNSName"]},
                    "HostedZoneId":{"Fn::GetAtt":["LoadBalancer","CanonicalHostedZoneID"]},
                    "EvaluateTargetHealth":false
                  }
                }}
              }
            }
            """.formatted(lbName, ZONE_NAME, FQDN));
    }

    private static void create(MiniStackTestSupport ministack, String stackName, ObjectNode template) {
        ministack.cloudFormation().createStack(CreateStackRequest.builder()
            .stackName(stackName)
            .templateBody(template.toString())
            .capabilities(Capability.CAPABILITY_IAM, Capability.CAPABILITY_NAMED_IAM)
            .build());
        ministack.cloudFormation().waiter().waitUntilStackCreateComplete(
            DescribeStacksRequest.builder().stackName(stackName).build());
    }

    private static void deleteQuietly(MiniStackTestSupport ministack, String stackName) {
        try {
            ministack.cloudFormation().deleteStack(
                DeleteStackRequest.builder().stackName(stackName).build());
            ministack.cloudFormation().waiter().waitUntilStackDeleteComplete(
                DescribeStacksRequest.builder().stackName(stackName).build());
        } catch (Exception ignored) {
            // Preserve original failure.
        }
    }

    private static Map<String, String> physicalIds(MiniStackTestSupport ministack, String stackName) {
        return ministack.cloudFormation().listStackResources(
                ListStackResourcesRequest.builder().stackName(stackName).build())
            .stackResourceSummaries().stream()
            .collect(Collectors.toMap(
                StackResourceSummary::logicalResourceId,
                StackResourceSummary::physicalResourceId,
                (a, b) -> a));
    }

    private static SecurityGroup describeSg(MiniStackTestSupport ministack, String groupId) {
        return ministack.ec2().describeSecurityGroups(
                DescribeSecurityGroupsRequest.builder().groupIds(groupId).build())
            .securityGroups().getFirst();
    }

    private static boolean hasCidrIngress(SecurityGroup sg, int port, String cidr) {
        for (IpPermission p : sg.ipPermissions()) {
            if (!matchesPort(p, port)) {
                continue;
            }
            if (p.ipRanges() != null && p.ipRanges().stream()
                .anyMatch(r -> cidr.equals(r.cidrIp()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyIngressPort(SecurityGroup sg, int port) {
        return sg.ipPermissions().stream().anyMatch(p -> matchesPort(p, port));
    }

    private static boolean adaptedTemplateHasPeerIngress(
            ObjectNode template, String sgLogicalId, int port, String peerLogicalId) {
        JsonNode ingress = template.path("Resources").path(sgLogicalId)
            .path("Properties").path("SecurityGroupIngress");
        if (!ingress.isArray()) {
            return false;
        }
        for (JsonNode rule : ingress) {
            if (rule.path("FromPort").asInt() == port
                && peerLogicalId.equals(rule.path("SourceSecurityGroupId").path("Ref").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPort(IpPermission p, int port) {
        Integer from = p.fromPort();
        Integer to = p.toPort();
        if (from == null || to == null) {
            return false;
        }
        return from <= port && to >= port;
    }

    private static boolean hasResourceType(ObjectNode template, String type) {
        JsonNode resources = template.path("Resources");
        if (!resources.isObject()) {
            return false;
        }
        for (JsonNode resource : resources) {
            if (type.equals(resource.path("Type").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String output(MiniStackTestSupport ministack, String stackName, String key) {
        return ministack.cloudFormation().describeStacks(
                DescribeStacksRequest.builder().stackName(stackName).build())
            .stacks().getFirst().outputs().stream()
            .filter(o -> key.equals(o.outputKey()))
            .findFirst().orElseThrow().outputValue();
    }

    private static String normalizeDns(String dns) {
        if (dns == null) {
            return "";
        }
        String value = dns.trim().toLowerCase(Locale.ROOT);
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    private static void assertReachable(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        Exception lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 500) {
                    assertTrue(response.body().contains("cfc-ministack"),
                        () -> "unexpected body from " + url + ": " + response.body());
                    return;
                }
                lastFailure = new IllegalStateException("HTTP " + response.statusCode());
            } catch (Exception e) {
                lastFailure = e;
            }
            Thread.sleep(Duration.ofSeconds(2));
        }
        throw new IllegalStateException("Endpoint did not become ready: " + url, lastFailure);
    }
}
