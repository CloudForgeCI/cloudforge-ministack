package com.cloudforgeci.ministack;

import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.route53.Route53Client;

import java.net.URI;
import java.time.Duration;

final class MiniStackTestSupport implements AutoCloseable {
    static final DockerImageName IMAGE = DockerImageName.parse("ministackorg/ministack:1.4.9");

    private final GenericContainer<?> container;
    private URI endpoint;
    private CloudFormationClient cloudFormation;
    private Ec2Client ec2;
    private ElasticLoadBalancingV2Client elb;
    private Route53Client route53;
    private AcmClient acm;

    MiniStackTestSupport() {
        // Only skip Testcontainers when AWS_ENDPOINT_URL is explicitly set
        if (envPresent("AWS_ENDPOINT_URL")) {
            container = null;
            endpoint = URI.create(MiniStackDeployer.resolveEndpoint());
        } else {
            container = new GenericContainer<>(IMAGE)
                .withExposedPorts(4566)
                .withEnv("MINISTACK_REGION", "us-east-1")
                .withEnv("PERSIST_STATE", "0")
                .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_WRITE)
                .waitingFor(Wait.forHttp("/_ministack/health")
                    .forPort(4566)
                    .withStartupTimeout(Duration.ofMinutes(2)));
        }
    }

    void start() {
        if (container != null) {
            container.start();
            endpoint = URI.create(
                "http://" + container.getHost() + ":" + container.getMappedPort(4566));
        }
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        cloudFormation = CloudFormationClient.builder()
            .endpointOverride(endpoint).credentialsProvider(credentials).region(Region.US_EAST_1).build();
        ec2 = Ec2Client.builder()
            .endpointOverride(endpoint).credentialsProvider(credentials).region(Region.US_EAST_1).build();
        elb = ElasticLoadBalancingV2Client.builder()
            .endpointOverride(endpoint).credentialsProvider(credentials).region(Region.US_EAST_1).build();
        route53 = Route53Client.builder()
            .endpointOverride(endpoint).credentialsProvider(credentials).region(Region.US_EAST_1).build();
        acm = AcmClient.builder()
            .endpointOverride(endpoint).credentialsProvider(credentials).region(Region.US_EAST_1).build();
    }

    CloudFormationClient cloudFormation() {
        return cloudFormation;
    }

    Ec2Client ec2() {
        return ec2;
    }

    ElasticLoadBalancingV2Client elb() {
        return elb;
    }

    Route53Client route53() {
        return route53;
    }

    AcmClient acm() {
        return acm;
    }

    String endpoint() {
        return endpoint.toString();
    }

    private static boolean envPresent(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    @Override
    public void close() {
        if (acm != null) acm.close();
        if (route53 != null) route53.close();
        if (elb != null) elb.close();
        if (ec2 != null) ec2.close();
        if (cloudFormation != null) cloudFormation.close();
        if (container != null) container.close();
    }
}
