# Local Emulator Quick Start

Use [cloudforge-cli](https://github.com/CloudForgeCI/cloudforge-cli) for every local platform
action and application deployment. MiniStack and LocalStack share gateway port `4566`, so run
only one at a time.

## Prerequisites

- [cloudforge-cli](https://github.com/CloudForgeCI/cloudforge-cli): `brew install CloudForgeCI/tap/cloudforge-cli`
- Docker.
- `LOCALSTACK_AUTH_TOKEN` only when using LocalStack.
- Optional friendly hostnames: `./scripts/setup-cloudforge-local-hosts.sh`.

## Start a platform

The lifecycle implementation lives in `cloudforge-ministack` and `cloudforge-localstack`;
`cloudforge-cli` discovers and invokes it through `PlatformRuntimeProvider`.

```bash
# Required before starting LocalStack.
export LOCALSTACK_AUTH_TOKEN=...

cloudforge-cli emulator start --target localstack   # or --target ministack
```

This starts the emulator and its companions (the StackPort resource browser on port 8888 and
the nginx emulator edge on port 80) and reconciles host routes. `stop`, `restart`, and `status`
work the same way; `restart` reconciles the edge again as part of the same call.

Verify the selected platform:

```bash
curl -s http://localhost:4566/_localstack/health
# or
curl -s http://localhost:4566/_ministack/health
```

## Deploy an application

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
cloudforge-cli deploy --context cfc-testing/deployment-contexts/Jenkins-Stack.json --target localstack
```

`--target` is `localstack` or `ministack`. Both synthesize the canonical template, adapt it for
the target, run a preflight check, and deploy it. The stack is named `<stackName>-ministack` or
`<stackName>-localstack`.

## Deploy CloudForge Manager

CloudForge Manager is provided by the `cloudforge-manager-deployment` artifact, which
`cfc-testing` depends on. Deploy it with
`cloudforge-cli deploy --context deployment-contexts/CloudForgeManager-Fresh.json --target localstack`
(or `--target ministack`). For MiniStack and LocalStack, its deployment extension:

1. uses the `cloudforgeci/cloudforge-manager` image, building it from a sibling
   `cloudforge-manager` source checkout when one exists, otherwise pulling the published
   image from Docker Hub;
2. deploys through the standard local target path;
3. reconciles the emulator edge; and
4. waits for `http://manager.cloudforge.localhost/api/v1/health`.

Contexts that set `provisionDatabase: true` (for example `CloudForgeManager-Dev.json`) deploy
to LocalStack only; MiniStack preflight blocks RDS. After deployment, open
`http://manager.cloudforge.localhost/`.

CloudForge Manager itself is developed in a separate repository.

## Troubleshooting

| Symptom | Resolution |
|---|---|
| Docker daemon unavailable | Start Docker Desktop, then `cloudforge-cli emulator start` again. |
| Port `4566` busy | `cloudforge-cli emulator stop --target <other platform>`. |
| LocalStack refuses to start | Export a valid `LOCALSTACK_AUTH_TOKEN`. |
| Application URL missing | `cloudforge-cli emulator restart --target <platform>` to reconcile the edge. |
| Manager health check fails | Confirm the deploy targeted `localstack` or `ministack`, restart to reconcile the edge, and inspect the Manager ECS task logs. |

See [MiniStack](https://github.com/CloudForgeCI/cfc-core/blob/develop/docs/ministack/README.md)
and [LocalStack](https://github.com/CloudForgeCI/cfc-core/blob/develop/docs/localstack/README.md)
for target-specific detail.
