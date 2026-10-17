package com.cloudforgeci.ministack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;

/**
 * CDK templates reference an SSM parameter that {@code cdk bootstrap} creates once per account and
 * region. MiniStack behaves like AWS here, so a region has to be bootstrapped before a CDK template
 * can deploy to it. This class detects that requirement and explains how to satisfy it; it never
 * bootstraps on its own.
 */
final class MiniStackCdkBootstrap {
    /** Account id MiniStack reports for every request. */

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MiniStackCdkBootstrap() {
    }

    /**
     * The SSM parameter path the template's {@code BootstrapVersion} parameter resolves, or empty
     * when the template does not depend on a bootstrapped environment.
     */
    static Optional<String> requiredParameter(String templateBody) throws IOException {
        JsonNode parameter = MAPPER.readTree(templateBody).path("Parameters").path("BootstrapVersion");
        if (!parameter.path("Type").asText("").startsWith("AWS::SSM::Parameter::Value")) {
            return Optional.empty();
        }
        String path = parameter.path("Default").asText("");
        return path.startsWith("/") ? Optional.of(path) : Optional.empty();
    }

    static String instructions(String endpoint, String region, String parameter) {
        return "This MiniStack has not been CDK-bootstrapped in " + region + " (SSM parameter "
            + parameter + " does not exist).\n\n"
            + "Bootstrap it once per account and region, as you would an AWS account:\n\n"
            + "  AWS_ENDPOINT_URL=" + endpoint + " AWS_ACCESS_KEY_ID=" + MiniStackCredentials.fromEnvironment().accessKeyId() + " AWS_SECRET_ACCESS_KEY=test \\\n"
            + "  AWS_DEFAULT_REGION=" + region + " \\\n"
            + "  cdk bootstrap aws://" + MiniStackCredentials.accountId() + "/" + region + "\n\n"
            + "Bootstrapping is per account and region and does not persist if the emulator container is recreated.";
    }
}
