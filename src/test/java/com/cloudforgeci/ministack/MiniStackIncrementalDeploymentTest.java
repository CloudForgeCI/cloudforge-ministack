package com.cloudforgeci.ministack;

import com.cloudforge.core.local.TemplateAdaptationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.ListStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ministack")
@Tag("integration")
class MiniStackIncrementalDeploymentTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void updatesOneStackAcrossLoadBalancerDomainAndTlsTransitions() throws Exception {
        String stackName = "cfc-transition-" + UUID.randomUUID().toString().substring(0, 8);

        try (MiniStackTestSupport ministack = new MiniStackTestSupport()) {
            ministack.start();
            try {

                ObjectNode loadBalancerOnly = template(false, null, false, false);
                create(ministack, stackName, loadBalancerOnly);
                assertTypes(ministack, stackName,
                    "AWS::EC2::VPC",
                    "AWS::ElasticLoadBalancingV2::LoadBalancer",
                    "AWS::ElasticLoadBalancingV2::Listener");
                assertReachable(output(ministack, stackName, "MiniStackLocalUrl"));

                ObjectNode domain = template(true, null, false, false);
                update(ministack, stackName, domain);
                assertTypes(ministack, stackName,
                    "AWS::Route53::HostedZone",
                    "AWS::Route53::RecordSet");

                ObjectNode subdomain = template(true, "ci", false, false);
                update(ministack, stackName, subdomain);
                assertEquals(
                    "ci.example.test.",
                    ministack.route53().listResourceRecordSets(builder ->
                        builder.hostedZoneId(firstHostedZoneId(ministack)))
                        .resourceRecordSets().stream()
                        .filter(record -> "ci.example.test.".equals(record.name()))
                        .findFirst().orElseThrow()
                        .name()
                );

                ObjectNode tls = template(true, "ci", true, false);
                update(ministack, stackName, tls);
                assertTypes(ministack, stackName, "AWS::CertificateManager::Certificate");

                update(ministack, stackName, loadBalancerOnly);
                assertFalse(resourceTypes(ministack, stackName).contains("AWS::Route53::HostedZone"));
                assertReachable(output(ministack, stackName, "MiniStackLocalUrl"));
            } finally {
                try {
                    ministack.cloudFormation().deleteStack(
                        DeleteStackRequest.builder().stackName(stackName).build());
                    ministack.cloudFormation().waiter().waitUntilStackDeleteComplete(
                        DescribeStacksRequest.builder().stackName(stackName).build());
                } catch (Exception ignored) {
                    // Preserve the original assertion/deployment failure.
                }
            }
        }
    }

    private static void create(
            MiniStackTestSupport ministack, String stackName, ObjectNode canonicalTemplate) {
        ObjectNode template = adapt(canonicalTemplate, stackName);
        ministack.cloudFormation().createStack(CreateStackRequest.builder()
            .stackName(stackName)
            .templateBody(template.toString())
            .capabilities(Capability.CAPABILITY_IAM, Capability.CAPABILITY_NAMED_IAM)
            .build());
        ministack.cloudFormation().waiter().waitUntilStackCreateComplete(
            DescribeStacksRequest.builder().stackName(stackName).build());
    }

    private static void update(
            MiniStackTestSupport ministack, String stackName, ObjectNode canonicalTemplate) {
        ObjectNode template = adapt(canonicalTemplate, stackName);
        ministack.cloudFormation().updateStack(UpdateStackRequest.builder()
            .stackName(stackName)
            .templateBody(template.toString())
            .capabilities(Capability.CAPABILITY_IAM, Capability.CAPABILITY_NAMED_IAM)
            .build());
        ministack.cloudFormation().waiter().waitUntilStackUpdateComplete(
            DescribeStacksRequest.builder().stackName(stackName).build());
    }

    private static ObjectNode adapt(ObjectNode canonicalTemplate, String stackName) {
        TemplateAdaptationResult result =
            MiniStackTemplateAdapter.INSTANCE.adapt(canonicalTemplate, stackName);
        assertTrue(result.template().path("Outputs").has("MiniStackLocalUrl"),
            () -> "adapter must add MiniStackLocalUrl: " + result.adaptations());
        return result.template();
    }

    private static Set<String> resourceTypes(
            MiniStackTestSupport ministack, String stackName) {
        return ministack.cloudFormation().listStackResources(
            ListStackResourcesRequest.builder().stackName(stackName).build())
            .stackResourceSummaries().stream()
            .map(resource -> resource.resourceType())
            .collect(Collectors.toSet());
    }

    private static void assertTypes(
            MiniStackTestSupport ministack, String stackName, String... expected) {
        Set<String> actual = resourceTypes(ministack, stackName);
        for (String type : expected) {
            assertTrue(actual.contains(type), () -> type + " missing from " + actual);
        }
    }

    private static String output(
            MiniStackTestSupport ministack, String stackName, String outputKey) {
        return ministack.cloudFormation().describeStacks(
            DescribeStacksRequest.builder().stackName(stackName).build())
            .stacks().getFirst().outputs().stream()
            .filter(output -> outputKey.equals(output.outputKey()))
            .findFirst().orElseThrow().outputValue();
    }

    private static String firstHostedZoneId(MiniStackTestSupport ministack) {
        return ministack.route53().listHostedZones().hostedZones().stream()
            .filter(zone -> "example.test.".equals(zone.name()))
            .findFirst().orElseThrow().id();
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
                    HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() < 500) {
                    assertTrue(
                        response.body().contains("cfc-ministack"),
                        () -> "unexpected body from " + url + ": " + response.body());
                    return;
                }
                lastFailure = new IllegalStateException(
                    "HTTP " + response.statusCode() + " from " + url);
            } catch (Exception e) {
                lastFailure = e;
            }
            Thread.sleep(Duration.ofSeconds(2));
        }
        throw new IllegalStateException("Endpoint did not become ready: " + url, lastFailure);
    }

    private static ObjectNode template(
            boolean domain, String subdomain, boolean tls, boolean auth) throws Exception {
        String domainResources = domain ? """
            ,"HostedZone":{"Type":"AWS::Route53::HostedZone","Properties":{"Name":"example.test."}}
            ,"DomainRecord":{"Type":"AWS::Route53::RecordSet","Properties":{
              "HostedZoneId":{"Ref":"HostedZone"},
              "Name":"%s",
              "Type":"CNAME",
              "TTL":"60",
              "ResourceRecords":[{"Fn::GetAtt":["LoadBalancer","DNSName"]}]
            }}
            """.formatted(subdomain == null ? "example.test." : subdomain + ".example.test.") : "";
        String tlsResources = tls ? """
            ,"Certificate":{"Type":"AWS::CertificateManager::Certificate","Properties":{
              "DomainName":"ci.example.test","ValidationMethod":"DNS"
            }}
            """: "";
        String authResources = auth ? """
            ,"UserPool":{"Type":"AWS::Cognito::UserPool","Properties":{
              "UserPoolName":"cfc-ministack-users"
            }}
            ,"AuthRule":{"Type":"AWS::ElasticLoadBalancingV2::ListenerRule","Properties":{
              "ListenerArn":{"Ref":"Listener"},
              "Priority":10,
              "Conditions":[{"Field":"path-pattern","Values":["/*"]}],
              "Actions":[
                {"Type":"authenticate-cognito","Order":1,"AuthenticateCognitoConfig":{
                  "UserPoolArn":{"Fn::GetAtt":["UserPool","Arn"]},
                  "UserPoolClientId":"local-client",
                  "UserPoolDomain":"local-domain"
                }},
                {"Type":"fixed-response","Order":2,"FixedResponseConfig":{
                  "StatusCode":"200","ContentType":"text/plain","MessageBody":"cfc-ministack-auth"
                }}
              ]
            }}
            """: "";

        return (ObjectNode) MAPPER.readTree("""
            {
              "AWSTemplateFormatVersion":"2010-09-09",
              "Resources":{
                "Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.42.0.0/16"}},
                "SubnetA":{"Type":"AWS::EC2::Subnet","Properties":{
                  "VpcId":{"Ref":"Vpc"},"CidrBlock":"10.42.1.0/24","AvailabilityZone":"us-east-1a"
                }},
                "SubnetB":{"Type":"AWS::EC2::Subnet","Properties":{
                  "VpcId":{"Ref":"Vpc"},"CidrBlock":"10.42.2.0/24","AvailabilityZone":"us-east-1b"
                }},
                "AlbSecurityGroup":{"Type":"AWS::EC2::SecurityGroup","Properties":{
                  "GroupDescription":"MiniStack ALB","VpcId":{"Ref":"Vpc"}
                }},
                "LoadBalancer":{"Type":"AWS::ElasticLoadBalancingV2::LoadBalancer","Properties":{
                  "Name":"cfc-ministack","Scheme":"internet-facing","Type":"application",
                  "Subnets":[{"Ref":"SubnetA"},{"Ref":"SubnetB"}],
                  "SecurityGroups":[{"Ref":"AlbSecurityGroup"}]
                }},
                "Listener":{"Type":"AWS::ElasticLoadBalancingV2::Listener","Properties":{
                  "LoadBalancerArn":{"Ref":"LoadBalancer"},"Port":80,"Protocol":"HTTP",
                  "DefaultActions":[{"Type":"fixed-response","FixedResponseConfig":{
                    "StatusCode":"200","ContentType":"text/plain","MessageBody":"cfc-ministack"
                  }}]
                }}
                %s%s%s
              }
            }
            """.formatted(domainResources, tlsResources, authResources));
    }
}
