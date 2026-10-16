package com.cloudforgeci.ministack;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniStackCdkBootstrapTest {
    private static final String BOOTSTRAP_TEMPLATE = """
        {"Parameters":{"BootstrapVersion":{"Type":"AWS::SSM::Parameter::Value<String>",
        "Default":"/cdk-bootstrap/hnb659fds/version"}},"Resources":{}}""";

    @Test
    void requiresTheParameterNamedByTheBootstrapVersionDefault() throws Exception {
        assertEquals(Optional.of("/cdk-bootstrap/hnb659fds/version"),
            MiniStackCdkBootstrap.requiredParameter(BOOTSTRAP_TEMPLATE));
    }

    @Test
    void doesNotRequireBootstrapWhenTheTemplateHasNoBootstrapParameter() throws Exception {
        assertEquals(Optional.empty(), MiniStackCdkBootstrap.requiredParameter("{\"Resources\":{}}"));
    }

    @Test
    void ignoresAPlainStringBootstrapVersion() throws Exception {
        String template = "{\"Parameters\":{\"BootstrapVersion\":{\"Type\":\"String\",\"Default\":\"21\"}}}";
        assertEquals(Optional.empty(), MiniStackCdkBootstrap.requiredParameter(template));
    }

    @Test
    void instructionsNameTheRegionEndpointAndCommand() {
        String text = MiniStackCdkBootstrap.instructions("http://localhost:4566", "eu-west-1",
            "/cdk-bootstrap/hnb659fds/version");

        assertTrue(text.contains("not been CDK-bootstrapped in eu-west-1"));
        assertTrue(text.contains("AWS_ENDPOINT_URL=http://localhost:4566"));
        assertTrue(text.contains("AWS_DEFAULT_REGION=eu-west-1"));
        assertTrue(text.contains("cdk bootstrap aws://000000000000/eu-west-1"));
    }
}
