package com.cloudforgeci.ministack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Cognito users and their group memberships for a MiniStack stack. MiniStack's CloudFormation cannot
 * create them, so the adapter records them in the template metadata and this class creates them
 * through the Cognito API once the user pool exists.
 */
final class MiniStackCognitoUsers {
    /** Template metadata key holding the users and memberships to create. */
    static final String METADATA_KEY = "CloudForgeMiniStackCognito";

    private static final Logger LOG = Logger.getLogger(MiniStackCognitoUsers.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MiniStackCognitoUsers() {
    }

    /** The users and group memberships declared by the template's resources, ready to store as metadata. */
    static ObjectNode record(ObjectNode resources) {
        ObjectNode recorded = MAPPER.createObjectNode();
        ArrayNode users = recorded.putArray("users");
        ArrayNode memberships = recorded.putArray("memberships");
        resources.properties().forEach(entry -> {
            JsonNode resource = entry.getValue();
            JsonNode props = resource.path("Properties");
            String pool = props.path("UserPoolId").path("Ref").asText(null);
            if (pool == null) {
                return;
            }
            switch (resource.path("Type").asText()) {
                case "AWS::Cognito::UserPoolUser" -> {
                    ObjectNode user = users.addObject();
                    user.put("pool", pool);
                    user.put("username", props.path("Username").asText());
                    ObjectNode attributes = user.putObject("attributes");
                    props.path("UserAttributes").forEach(attribute ->
                        attributes.put(attribute.path("Name").asText(), attribute.path("Value").asText()));
                }
                case "AWS::Cognito::UserPoolUserToGroupAttachment" -> {
                    ObjectNode membership = memberships.addObject();
                    membership.put("pool", pool);
                    membership.put("username", props.path("Username").asText());
                    membership.put("group", props.path("GroupName").asText());
                }
                default -> { }
            }
        });
        return recorded;
    }

    /**
     * The password every locally created user gets. Derived from the pool and user name so a redeploy
     * finds the same one; it protects nothing, since the pool only exists inside a local emulator.
     */
    static String localPassword(String pool, String username) {
        return MiniStackDatabases.localPassword("cognito:" + pool + ":" + username) + "!";
    }

    /** What was created, for the deploy log. */
    record Created(String username, String password) {
    }

    /**
     * Creates the users and memberships recorded in the template.
     *
     * @param poolPhysicalId resolves a pool's logical id to the id MiniStack gave it
     */
    static List<Created> create(
            JsonNode recorded, String endpoint, String region, Function<String, String> poolPhysicalId) {
        List<Created> created = new ArrayList<>();
        if (recorded == null || recorded.isMissingNode()) {
            return created;
        }
        try (CognitoIdentityProviderClient cognito = CognitoIdentityProviderClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(MiniStackCredentials.fromEnvironment()))
                .build()) {
            for (JsonNode user : recorded.path("users")) {
                String logicalPool = user.path("pool").asText();
                String pool = poolPhysicalId.apply(logicalPool);
                String username = user.path("username").asText();
                List<AttributeType> attributes = new ArrayList<>();
                user.path("attributes").properties().forEach(a ->
                    attributes.add(AttributeType.builder().name(a.getKey()).value(a.getValue().asText()).build()));
                try {
                    cognito.adminCreateUser(r -> r.userPoolId(pool).username(username)
                        .userAttributes(attributes).messageAction(MessageActionType.SUPPRESS));
                } catch (UsernameExistsException e) {
                    // a redeploy: the user is already there
                }
                String password = localPassword(logicalPool, username);
                cognito.adminSetUserPassword(r -> r.userPoolId(pool).username(username)
                    .password(password).permanent(true));
                created.add(new Created(username, password));
            }
            for (JsonNode membership : recorded.path("memberships")) {
                String pool = poolPhysicalId.apply(membership.path("pool").asText());
                cognito.adminAddUserToGroup(r -> r.userPoolId(pool)
                    .username(membership.path("username").asText())
                    .groupName(membership.path("group").asText()));
            }
        }
        created.forEach(c -> LOG.info("MiniStack Cognito user " + c.username() + " can sign in with password " + c.password()));
        return created;
    }
}
