package com.cloudforgeci.ministack;

import com.cloudforgeci.ministack.MiniStackDatabases.Endpoint;
import com.cloudforgeci.ministack.MiniStackDatabases.Plan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniStackDatabasesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TEMPLATE = """
        {
          "Resources": {
            "Db": {
              "Type": "AWS::RDS::DBInstance",
              "Properties": {
                "DBInstanceIdentifier": "ms-app-db",
                "Engine": "mysql", "EngineVersion": "8.0", "DBInstanceClass": "db.t3.small",
                "AllocatedStorage": "50", "DBName": "app",
                "MasterUsername": {"Fn::Join": ["", ["{{resolve:secretsmanager:", {"Ref": "Secret"}, ":SecretString:username::}}"]]},
                "MasterUserPassword": {"Fn::Join": ["", ["{{resolve:secretsmanager:", {"Ref": "Secret"}, ":SecretString:password::}}"]]}
              }
            },
            "Replica": {
              "Type": "AWS::RDS::DBInstance",
              "Properties": {"SourceDBInstanceIdentifier": {"Ref": "Db"}, "DBInstanceClass": "db.t3.small"}
            },
            "Secret": {
              "Type": "AWS::SecretsManager::Secret",
              "Properties": {"GenerateSecretString": {
                "GenerateStringKey": "password", "SecretStringTemplate": "{\\"username\\":\\"appadmin\\"}"}}
            },
            "Attachment": {
              "Type": "AWS::SecretsManager::SecretTargetAttachment",
              "Properties": {"SecretId": {"Ref": "Secret"}, "TargetId": {"Ref": "Db"}, "TargetType": "AWS::RDS::DBInstance"}
            },
            "Service": {
              "Type": "AWS::ECS::Service",
              "DependsOn": ["Attachment", "Other"],
              "Properties": {"Env": [
                {"Name": "DB_HOST", "Value": {"Fn::GetAtt": ["Db", "Endpoint.Address"]}},
                {"Name": "DB_PORT", "Value": {"Fn::GetAtt": ["Db", "Endpoint.Port"]}},
                {"Name": "REPLICA", "Value": {"Fn::GetAtt": ["Replica", "Endpoint.Address"]}},
                {"Name": "DB_ID", "Value": {"Ref": "Db"}}
              ]}
            },
            "Task": {
              "Type": "AWS::ECS::TaskDefinition",
              "Properties": {"ContainerDefinitions": [{
                "Name": "app",
                "Environment": [{"Name": "KEEP", "Value": "1"}],
                "Secrets": [
                  {"Name": "DB_PASSWORD", "ValueFrom": {"Fn::Join": ["", [{"Ref": "Secret"}, ":password::"]]}},
                  {"Name": "LICENSE", "ValueFrom": {"Fn::Join": ["", [{"Ref": "Unrelated"}, ":key::"]]}}
                ]
              }]}
            },
            "Other": {"Type": "AWS::SNS::Topic"}
          }
        }
        """;

    private static ObjectNode template() throws Exception {
        return (ObjectNode) MAPPER.readTree(TEMPLATE);
    }

    @Test
    void plansThePrimaryInstanceAndSkipsReadReplicas() throws Exception {
        List<Plan> plans = MiniStackDatabases.plan(template());

        assertEquals(1, plans.size());
        Plan plan = plans.getFirst();
        assertEquals("Db", plan.logicalId());
        assertEquals("ms-app-db", plan.identifier());
        assertEquals("mysql", plan.engine());
        assertEquals("8.0", plan.version());
        assertEquals(50, plan.allocatedStorageGb());
        assertEquals("app", plan.databaseName());
        assertEquals("Secret", plan.secretLogicalId());
        assertEquals("appadmin", plan.username(), "the user name comes from the secret's template");
    }

    @Test
    void theLocalPasswordIsStablePerInstanceAndDiffersBetweenInstances() {
        assertEquals(MiniStackDatabases.localPassword("a"), MiniStackDatabases.localPassword("a"));
        assertNotEquals(MiniStackDatabases.localPassword("a"), MiniStackDatabases.localPassword("b"));
        assertTrue(MiniStackDatabases.localPassword("a").matches("[A-Za-z0-9]{20,}"));
    }

    @Test
    void rewriteStoresTheRealConnectionInTheSecretAndRemovesTheInstanceAndAttachment() throws Exception {
        ObjectNode original = template();
        List<Plan> plans = MiniStackDatabases.plan(original);

        ObjectNode result = MiniStackDatabases.rewrite(original, plans, Map.of("Db", new Endpoint("db-host", 3306)));

        JsonNode resources = result.path("Resources");
        assertFalse(resources.has("Db"));
        assertFalse(resources.has("Replica"));
        assertFalse(resources.has("Attachment"));
        JsonNode secret = MAPPER.readTree(resources.path("Secret").path("Properties").path("SecretString").asText());
        assertEquals("appadmin", secret.path("username").asText());
        assertEquals(MiniStackDatabases.localPassword("ms-app-db"), secret.path("password").asText());
        assertEquals("db-host", secret.path("host").asText());
        assertEquals(3306, secret.path("port").asInt());
        assertEquals("app", secret.path("dbname").asText());
        assertFalse(resources.path("Secret").path("Properties").has("GenerateSecretString"));
    }

    @Test
    void rewriteFillsEndpointReferencesAndDropsDanglingDependencies() throws Exception {
        ObjectNode original = template();
        ObjectNode result = MiniStackDatabases.rewrite(
            original, MiniStackDatabases.plan(original), Map.of("Db", new Endpoint("db-host", 3306)));

        JsonNode env = result.path("Resources").path("Service").path("Properties").path("Env");
        assertEquals("db-host", env.get(0).path("Value").asText());
        assertEquals("3306", env.get(1).path("Value").asText());
        assertEquals("db-host", env.get(2).path("Value").asText(), "a replica's endpoint is the primary's");
        assertEquals("ms-app-db", env.get(3).path("Value").asText());
        assertEquals(List.of("Other"), List.of(result.path("Resources").path("Service").path("DependsOn").get(0).asText()));
        assertEquals(1, result.path("Resources").path("Service").path("DependsOn").size());
    }

    @Test
    void rewriteRecordsTheProvisionedIdentifiersSoDestroyCanRemoveThem() throws Exception {
        ObjectNode original = template();
        ObjectNode result = MiniStackDatabases.rewrite(
            original, MiniStackDatabases.plan(original), Map.of("Db", new Endpoint("db-host", 3306)));

        assertEquals(List.of("ms-app-db"), MiniStackDatabases.provisionedIdentifiers(result));
        assertTrue(MiniStackDatabases.provisionedIdentifiers(original).isEmpty());
    }

    @Test
    void aTemplateWithoutADatabaseHasNothingToPlan() throws Exception {
        assertTrue(MiniStackDatabases.plan(MAPPER.readTree("{\"Resources\":{\"T\":{\"Type\":\"AWS::SNS::Topic\"}}}")).isEmpty());
    }

    @Test
    void rewriteTurnsDatabaseSecretReferencesIntoEnvironmentVariablesAndKeepsOtherSecrets() throws Exception {
        ObjectNode original = template();
        ObjectNode result = MiniStackDatabases.rewrite(
            original, MiniStackDatabases.plan(original), Map.of("Db", new Endpoint("db-host", 3306)));

        JsonNode container = result.path("Resources").path("Task").path("Properties").path("ContainerDefinitions").get(0);
        assertEquals("KEEP", container.path("Environment").get(0).path("Name").asText());
        assertEquals("DB_PASSWORD", container.path("Environment").get(1).path("Name").asText());
        assertEquals(MiniStackDatabases.localPassword("ms-app-db"), container.path("Environment").get(1).path("Value").asText());
        assertEquals(1, container.path("Secrets").size(), "a secret that is not ours stays a secret");
        assertEquals("LICENSE", container.path("Secrets").get(0).path("Name").asText());
    }

    @Test
    void connectsByContainerNameWhenItFitsInADnsLabelAndByAddressWhenItDoesNot() {
        String shortName = "ministack-rds-281afe4a44d7-instance-ms-bagisto-bagisto-db";
        String longName = "ministack-rds-281afe4a44d7-instance-lv-mgr-mysql80-cloudforge-manager-db";

        assertEquals(shortName, MiniStackDatabaseProvisioner.chooseHost(shortName, "172.18.0.5"));
        assertTrue(longName.length() > 63);
        assertEquals("172.18.0.6", MiniStackDatabaseProvisioner.chooseHost(longName, "172.18.0.6"));
        assertEquals("172.18.0.7", MiniStackDatabaseProvisioner.chooseHost(null, "172.18.0.7"));
        assertEquals("host.docker.internal", MiniStackDatabaseProvisioner.chooseHost(null, "localhost"));
    }

    @Test
    void theNetworkAliasIsShortStableAndDifferentPerDatabase() {
        String alias = MiniStackDatabaseProvisioner.aliasFor("lv-mgr-mysql80-cloudforge-manager-db");

        assertEquals(alias, MiniStackDatabaseProvisioner.aliasFor("lv-mgr-mysql80-cloudforge-manager-db"));
        assertNotEquals(alias, MiniStackDatabaseProvisioner.aliasFor("ms-bagisto-bagisto-db"));
        assertTrue(alias.matches("db-[a-z0-9]{12}"), alias);
    }

    @Test
    void theReadinessProbeUsesTheAdminToolEachImageActuallyShips() {
        assertEquals("mariadb-admin",
            MiniStackDatabaseProvisioner.readinessProbe("mariadb", "c", "u", "p")[5]);
        assertEquals("mysqladmin",
            MiniStackDatabaseProvisioner.readinessProbe("mysql", "c", "u", "p")[5]);
        assertEquals("pg_isready",
            MiniStackDatabaseProvisioner.readinessProbe("postgres", "c", "u", "p")[3]);
    }

    @Test
    void compactingRemovesWhitespaceAndATemplateOverTheInlineLimitGoesThroughS3() throws Exception {
        String pretty = "{\n  \"Resources\" : {\n    \"T\" : {\"Type\" : \"AWS::SNS::Topic\"}\n  }\n}";
        assertEquals("{\"Resources\":{\"T\":{\"Type\":\"AWS::SNS::Topic\"}}}", MiniStackDeployer.compact(pretty));

        String huge = "{\"Resources\":{\"T\":{\"Type\":\"AWS::SNS::Topic\",\"Properties\":{\"DisplayName\":\""
            + "x".repeat(MiniStackDeployer.MAX_INLINE_TEMPLATE_BYTES) + "\"}}}}";
        Assertions.assertTrue(MiniStackDeployer.fitsInline(MiniStackDeployer.compact(pretty)));
        Assertions.assertFalse(MiniStackDeployer.fitsInline(MiniStackDeployer.compact(huge)));
    }

    @Test
    void generatedSecretsGetLocalValuesAndReachTheContainerAsEnvironment() throws Exception {
        ObjectNode template = (ObjectNode) MAPPER.readTree("""
            {"Resources": {
              "Admin": {"Type": "AWS::SecretsManager::Secret",
                "Properties": {"GenerateSecretString": {"PasswordLength": 24}}},
              "Json": {"Type": "AWS::SecretsManager::Secret",
                "Properties": {"GenerateSecretString": {
                  "SecretStringTemplate": "{\\"username\\":\\"admin\\"}", "GenerateStringKey": "password"}}},
              "Task": {"Type": "AWS::ECS::TaskDefinition", "Properties": {"ContainerDefinitions": [{
                "Name": "app",
                "Secrets": [
                  {"Name": "POSTGRES_PASSWORD", "ValueFrom": {"Ref": "Admin"}},
                  {"Name": "APP_PASSWORD", "ValueFrom": {"Fn::Join": ["", [{"Ref": "Json"}, ":password::"]]}},
                  {"Name": "OTHER", "ValueFrom": "arn:aws:secretsmanager:us-east-1:1:secret:other"}]}]}}
            }}""");

        ObjectNode result = MiniStackDatabases.inlineGeneratedSecrets(template);

        JsonNode container = result.path("Resources").path("Task").path("Properties")
            .path("ContainerDefinitions").get(0);
        Map<String, String> environment = new HashMap<>();
        container.path("Environment").forEach(e -> environment.put(e.path("Name").asText(), e.path("Value").asText()));
        assertEquals(2, environment.size());
        assertTrue(environment.get("POSTGRES_PASSWORD").startsWith("Cfc"));
        assertEquals(environment.get("APP_PASSWORD"),
            MAPPER.readTree(result.path("Resources").path("Json").path("Properties").path("SecretString").asText())
                .path("password").asText());
        assertEquals(1, container.path("Secrets").size());
        assertEquals("OTHER", container.path("Secrets").get(0).path("Name").asText());
        assertFalse(result.path("Resources").path("Admin").path("Properties").has("GenerateSecretString"));
        assertEquals(result, MiniStackDatabases.inlineGeneratedSecrets(template), "values are stable across deploys");
    }

    @Test
    void anExistingDatabaseIsReusedOnlyWhileItsContainerRuns() {
        assertTrue(MiniStackDatabaseProvisioner.reusable(true, "ministack-rds-1-instance-db"));
        assertFalse(MiniStackDatabaseProvisioner.reusable(true, null), "listed by the emulator but its container is gone");
        assertFalse(MiniStackDatabaseProvisioner.reusable(false, "ministack-rds-1-instance-db"));
        assertFalse(MiniStackDatabaseProvisioner.reusable(false, null));
    }

    @Test
    void aGeneratedSecretHasTheLengthItAsksFor() throws Exception {
        ObjectNode template = (ObjectNode) new ObjectMapper().readTree("""
            {"Resources":{"Key":{"Type":"AWS::SecretsManager::Secret","Properties":{
              "GenerateSecretString":{"PasswordLength":32,"ExcludePunctuation":true}}}}}""");

        String value = MiniStackDatabases.inlineGeneratedSecrets(template)
            .path("Resources").path("Key").path("Properties").path("SecretString").asText();

        assertEquals(32, value.length());
        assertTrue(value.matches("[A-Za-z0-9]+"));
        assertEquals(value, MiniStackDatabases.localPassword("secret:Key", 32));
    }
}
