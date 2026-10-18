package com.cloudforgeci.ministack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MiniStack records an {@code AWS::RDS::DBInstance} created through CloudFormation but does not start
 * a database for it; only its direct RDS API does. This class finds the instances in a template and
 * rewrites the template to point at databases created through that API, so an application gets a
 * database it can connect to.
 *
 * <p>This is the planning and rewriting half, with no side effects. {@link MiniStackDatabaseProvisioner}
 * creates the databases.</p>
 */
final class MiniStackDatabases {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Template metadata key listing the databases created for the stack, so destroy can remove them. */
    static final String METADATA_KEY = "CloudForgeMiniStackDatabases";

    private MiniStackDatabases() {
    }

    /** The identifiers of the databases a deployed template provisioned. */
    static List<String> provisionedIdentifiers(JsonNode template) {
        List<String> identifiers = new ArrayList<>();
        template.path("Metadata").path(METADATA_KEY).forEach(item -> identifiers.add(item.asText()));
        return List.copyOf(identifiers);
    }

    /** One database the template asks for. {@code secretLogicalId} is null when it has no secret. */
    record Plan(
            String logicalId,
            String identifier,
            String engine,
            String version,
            String instanceClass,
            int allocatedStorageGb,
            String databaseName,
            String username,
            String secretLogicalId) {
    }

    /** Where a created database can be reached from the application containers. */
    record Endpoint(String host, int port) {
    }

    /** The primary instances in the template. Read replicas are not provisioned. */
    static List<Plan> plan(JsonNode template) {
        List<Plan> plans = new ArrayList<>();
        JsonNode resources = template.path("Resources");
        resources.properties().forEach(entry -> {
            JsonNode resource = entry.getValue();
            if (!"AWS::RDS::DBInstance".equals(resource.path("Type").asText())
                    || resource.path("Properties").has("SourceDBInstanceIdentifier")) {
                return;
            }
            JsonNode props = resource.path("Properties");
            String identifier = props.path("DBInstanceIdentifier").isTextual()
                ? props.path("DBInstanceIdentifier").asText()
                : entry.getKey().toLowerCase();
            String secret = secretLogicalId(props.path("MasterUserPassword"));
            plans.add(new Plan(
                entry.getKey(),
                identifier,
                props.path("Engine").asText("mysql"),
                props.path("EngineVersion").asText(""),
                props.path("DBInstanceClass").asText("db.t3.micro"),
                props.path("AllocatedStorage").asInt(20),
                props.path("DBName").asText(null),
                username(resources, secret, props.path("MasterUsername")),
                secret));
        });
        return List.copyOf(plans);
    }

    /**
     * The password every instance uses locally. It is derived from the identifier so a redeploy finds
     * the same password for a database that already exists. It protects nothing: the database only
     * exists inside a local emulator.
     */
    static String localPassword(String identifier) {
        return localPassword(identifier, 0);
    }

    /**
     * The local password stretched to {@code length} characters when that is longer than the default, for a
     * generated secret that asks for a {@code PasswordLength} (an AES key must decode to a fixed number of bytes).
     */
    static String localPassword(String identifier, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("cfc-ministack-db:" + identifier).getBytes(StandardCharsets.UTF_8));
            StringBuilder password = new StringBuilder("Cfc");
            for (int i = 0; i < 12; i++) {
                password.append(Integer.toHexString((digest[i] & 0xff) | 0x100).substring(1));
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            while (password.length() < length) {
                digest = sha.digest(digest);
                for (int i = 0; i < digest.length && password.length() < length; i++) {
                    password.append(Integer.toHexString((digest[i] & 0xff) | 0x100).substring(1));
                }
            }
            return length > 0 && length < password.length() ? password.substring(0, length) : password.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Points the template at the created databases: the instance and its secret attachment are
     * removed, the secret carries the real connection details, and every endpoint reference is
     * replaced with the real host and port.
     */
    static ObjectNode rewrite(ObjectNode template, List<Plan> plans, Map<String, Endpoint> endpoints) {
        ObjectNode result = template.deepCopy();
        ObjectNode resources = (ObjectNode) result.path("Resources");
        Set<String> removed = new HashSet<>();
        Map<String, Plan> byLogicalId = new LinkedHashMap<>();
        plans.forEach(plan -> byLogicalId.put(plan.logicalId(), plan));

        Map<String, JsonNode> secretValues = new LinkedHashMap<>();
        for (Plan plan : plans) {
            Endpoint endpoint = endpoints.get(plan.logicalId());
            if (plan.secretLogicalId() != null && resources.has(plan.secretLogicalId())) {
                ObjectNode secret = (ObjectNode) resources.get(plan.secretLogicalId());
                ObjectNode props = (ObjectNode) secret.path("Properties");
                props.remove("GenerateSecretString");
                ObjectNode value = MAPPER.createObjectNode();
                value.put("username", plan.username());
                value.put("password", localPassword(plan.identifier()));
                value.put("engine", plan.engine());
                value.put("host", endpoint.host());
                value.put("port", endpoint.port());
                value.put("dbname", plan.databaseName() == null ? "" : plan.databaseName());
                props.put("SecretString", value.toString());
                secretValues.put(plan.secretLogicalId(), value);
            }
        }

        // Replicas and attachments that point at a primary go with it.
        resources.properties().forEach(entry -> {
            JsonNode resource = entry.getValue();
            String type = resource.path("Type").asText();
            if ("AWS::RDS::DBInstance".equals(type)) {
                removed.add(entry.getKey());
            } else if ("AWS::SecretsManager::SecretTargetAttachment".equals(type)
                    && byLogicalId.containsKey(resource.path("Properties").path("TargetId").path("Ref").asText())) {
                removed.add(entry.getKey());
            }
        });
        removed.forEach(resources::remove);

        Map<String, Plan> replicaPrimary = new LinkedHashMap<>();
        template.path("Resources").properties().forEach(entry -> {
            JsonNode source = entry.getValue().path("Properties").path("SourceDBInstanceIdentifier");
            if ("AWS::RDS::DBInstance".equals(entry.getValue().path("Type").asText()) && !source.isMissingNode()) {
                byLogicalId.values().stream()
                    .filter(plan -> plan.identifier().equals(source.asText()) || source.path("Ref").asText().equals(plan.logicalId()))
                    .findFirst()
                    .ifPresent(plan -> replicaPrimary.put(entry.getKey(), plan));
            }
        });

        ObjectNode rewritten = (ObjectNode) substitute(result, removed, byLogicalId, replicaPrimary, endpoints);
        inlineSecrets((ObjectNode) rewritten.path("Resources"), secretValues);
        ArrayNode identifiers = MAPPER.createArrayNode();
        plans.forEach(plan -> identifiers.add(plan.identifier()));
        ObjectNode metadata = rewritten.has("Metadata") && rewritten.get("Metadata").isObject()
            ? (ObjectNode) rewritten.get("Metadata")
            : rewritten.putObject("Metadata");
        metadata.set(METADATA_KEY, identifiers);
        return rewritten;
    }

    /**
     * Secrets that CloudFormation would generate (an application's initial admin or database
     * password, for example) get a fixed local value, and the containers that read them receive it as
     * a plain environment variable, since MiniStack drops the entries of an ECS {@code Secrets} list.
     * The value is derived from the secret's logical id, so a redeploy keeps it. Secrets that already
     * carry a value, such as the database secrets {@link #rewrite} fills in, are left alone.
     */
    static ObjectNode inlineGeneratedSecrets(ObjectNode template) {
        ObjectNode result = template.deepCopy();
        ObjectNode resources = (ObjectNode) result.path("Resources");
        Map<String, JsonNode> secretValues = new LinkedHashMap<>();
        resources.properties().forEach(entry -> {
            ObjectNode resource = (ObjectNode) entry.getValue();
            JsonNode generate = resource.path("Properties").path("GenerateSecretString");
            if (!"AWS::SecretsManager::Secret".equals(resource.path("Type").asText()) || !generate.isObject()) {
                return;
            }
            String password = localPassword("secret:" + entry.getKey(), generate.path("PasswordLength").asInt(0));
            String key = generate.path("GenerateStringKey").asText("");
            JsonNode value;
            if (key.isEmpty()) {
                value = MAPPER.getNodeFactory().textNode(password);
            } else {
                ObjectNode json;
                try {
                    json = generate.path("SecretStringTemplate").isTextual()
                        ? (ObjectNode) MAPPER.readTree(generate.path("SecretStringTemplate").asText())
                        : MAPPER.createObjectNode();
                } catch (IOException e) {
                    throw new IllegalStateException("Unreadable SecretStringTemplate on " + entry.getKey(), e);
                }
                json.put(key, password);
                value = json;
            }
            ObjectNode properties = (ObjectNode) resource.path("Properties");
            properties.remove("GenerateSecretString");
            properties.put("SecretString", value.isTextual() ? value.asText() : value.toString());
            secretValues.put(entry.getKey(), value);
        });
        inlineSecrets(resources, secretValues);
        return result;
    }

    /**
     * MiniStack drops the entries of an ECS container's {@code Secrets} list, so the application never
     * receives them. For the database secrets, whose values are known here, each entry becomes a plain
     * environment variable instead. The value is the one stored in the local secret.
     */
    private static void inlineSecrets(ObjectNode resources, Map<String, JsonNode> secretValues) {
        if (secretValues.isEmpty()) {
            return;
        }
        resources.properties().forEach(entry -> {
            if (!"AWS::ECS::TaskDefinition".equals(entry.getValue().path("Type").asText())) {
                return;
            }
            for (JsonNode container : entry.getValue().path("Properties").path("ContainerDefinitions")) {
                if (!(container instanceof ObjectNode containerNode) || !container.path("Secrets").isArray()) {
                    continue;
                }
                ArrayNode kept = MAPPER.createArrayNode();
                for (JsonNode secret : container.path("Secrets")) {
                    String value = inlinedValue(secret.path("ValueFrom"), secretValues);
                    if (value == null) {
                        kept.add(secret);
                        continue;
                    }
                    ArrayNode environment = container.path("Environment").isArray()
                        ? (ArrayNode) container.path("Environment")
                        : containerNode.putArray("Environment");
                    environment.addObject().put("Name", secret.path("Name").asText()).put("Value", value);
                }
                if (kept.isEmpty()) {
                    containerNode.remove("Secrets");
                } else {
                    containerNode.set("Secrets", kept);
                }
            }
        });
    }

    /** The stored value a {@code <secret>:<field>::} reference points at, or null when it is not one of ours. */
    private static String inlinedValue(JsonNode valueFrom, Map<String, JsonNode> secretValues) {
        if (valueFrom.size() == 1 && valueFrom.has("Ref")) {
            JsonNode whole = secretValues.get(valueFrom.path("Ref").asText());
            return whole != null && whole.isTextual() ? whole.asText() : null;
        }
        String reference = null;
        String suffix = null;
        for (JsonNode part : valueFrom.path("Fn::Join").path(1)) {
            if (part.has("Ref") && secretValues.containsKey(part.path("Ref").asText())) {
                reference = part.path("Ref").asText();
            } else if (part.isTextual() && reference != null) {
                suffix = part.asText();
            }
        }
        if (reference == null || suffix == null || !suffix.startsWith(":")) {
            return null;
        }
        String field = suffix.substring(1, suffix.indexOf(':', 1) < 0 ? suffix.length() : suffix.indexOf(':', 1));
        JsonNode value = secretValues.get(reference).path(field);
        return value.isMissingNode() ? null : value.asText();
    }

    private static JsonNode substitute(
            JsonNode node,
            Set<String> removed,
            Map<String, Plan> byLogicalId,
            Map<String, Plan> replicaPrimary,
            Map<String, Endpoint> endpoints) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            JsonNode getAtt = object.get("Fn::GetAtt");
            if (object.size() == 1 && getAtt != null && getAtt.isArray() && getAtt.size() == 2) {
                String target = getAtt.get(0).asText();
                Plan plan = byLogicalId.containsKey(target) ? byLogicalId.get(target) : replicaPrimary.get(target);
                if (plan != null) {
                    Endpoint endpoint = endpoints.get(plan.logicalId());
                    String attribute = getAtt.get(1).asText();
                    if ("Endpoint.Address".equals(attribute)) {
                        return MAPPER.getNodeFactory().textNode(endpoint.host());
                    }
                    if ("Endpoint.Port".equals(attribute)) {
                        return MAPPER.getNodeFactory().textNode(String.valueOf(endpoint.port()));
                    }
                }
            }
            if (object.size() == 1 && object.has("Ref") && byLogicalId.containsKey(object.get("Ref").asText())) {
                return MAPPER.getNodeFactory().textNode(byLogicalId.get(object.get("Ref").asText()).identifier());
            }
            if (object.has("DependsOn")) {
                JsonNode dependsOn = object.get("DependsOn");
                if (dependsOn.isArray()) {
                    ArrayNode kept = MAPPER.createArrayNode();
                    dependsOn.forEach(item -> {
                        if (!removed.contains(item.asText())) {
                            kept.add(item);
                        }
                    });
                    if (kept.isEmpty()) {
                        object.remove("DependsOn");
                    } else {
                        object.set("DependsOn", kept);
                    }
                } else if (removed.contains(dependsOn.asText())) {
                    object.remove("DependsOn");
                }
            }
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                object.set(name, substitute(object.get(name), removed, byLogicalId, replicaPrimary, endpoints));
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, substitute(array.get(i), removed, byLogicalId, replicaPrimary, endpoints));
            }
        }
        return node;
    }

    private static String secretLogicalId(JsonNode password) {
        for (JsonNode part : password.path("Fn::Join").path(1)) {
            if (part.has("Ref")) {
                return part.path("Ref").asText();
            }
        }
        return null;
    }

    private static String username(JsonNode resources, String secretLogicalId, JsonNode masterUsername) {
        if (masterUsername.isTextual() && !masterUsername.asText().contains("{{resolve")) {
            return masterUsername.asText();
        }
        if (secretLogicalId != null) {
            String template = resources.path(secretLogicalId).path("Properties")
                .path("GenerateSecretString").path("SecretStringTemplate").asText("");
            try {
                String name = MAPPER.readTree(template).path("username").asText("");
                if (!name.isBlank()) {
                    return name;
                }
            } catch (Exception ignored) {
                // fall through to the default
            }
        }
        return "admin";
    }
}
