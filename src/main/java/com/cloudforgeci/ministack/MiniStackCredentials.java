package com.cloudforgeci.ministack;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;

/**
 * The credentials the module signs MiniStack requests with. MiniStack gives each 12-digit access key
 * its own account, so setting {@code AWS_ACCESS_KEY_ID=111111111111} deploys into account
 * {@code 111111111111}, just as {@code AWS_DEFAULT_REGION} chooses the region. Anything else is the
 * default account {@code 000000000000}, reached with the conventional {@code test} key.
 */
final class MiniStackCredentials {
    static final String DEFAULT_ACCOUNT = "000000000000";

    private MiniStackCredentials() {
    }

    /** Credentials for the account named by {@code AWS_ACCESS_KEY_ID}, or the default account. */
    static AwsBasicCredentials fromEnvironment() {
        return forAccessKey(System.getenv("AWS_ACCESS_KEY_ID"));
    }

    static AwsBasicCredentials forAccessKey(String accessKey) {
        return AwsBasicCredentials.create(isAccountId(accessKey) ? accessKey : "test", "test");
    }

    /** The account the credentials of {@link #fromEnvironment()} belong to. */
    static String accountId() {
        String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        return isAccountId(accessKey) ? accessKey : DEFAULT_ACCOUNT;
    }

    static boolean isAccountId(String value) {
        return value != null && value.matches("\\d{12}");
    }
}
