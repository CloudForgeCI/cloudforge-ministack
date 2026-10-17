# cloudforge-ministack

MiniStack target for CloudForge. It lets `cloudforge-cli` deploy CloudForge applications to a local
[MiniStack](https://github.com/ministackorg/ministack) emulator: the CloudFormation template CDK
synthesizes is adapted for the emulator, the databases it asks for are started, and the stack is
deployed to `http://localhost:4566`.

This module is discovered by `cloudforge-cli`; you do not run it directly.

## Prerequisites

| Tool | Used for |
|---|---|
| Docker | Runs MiniStack, the application containers and any databases |
| [cloudforge-cli](https://github.com/CloudForgeCI/cloudforge-cli) | `brew install CloudForgeCI/tap/cloudforge-cli` |
| AWS CDK CLI (`npm install -g aws-cdk`) | One-time `cdk bootstrap` of the emulator |
| AWS CLI (optional) | Inspecting what was deployed |

MiniStack and LocalStack both listen on port `4566`. Run one at a time.

Give Docker enough memory. A single application with a database needs about 2 GB; the Docker Desktop
default of half your RAM is usually fine, but a large allocation on a small machine makes the host swap.

## 1. Start MiniStack

```bash
cloudforge-cli emulator start --target ministack
cloudforge-cli emulator status                      # reports MiniStack and LocalStack
```

This starts the emulator (`ministackorg/ministack`, version pinned by CloudForge), the StackPort
resource browser on port `8888`, and the nginx edge on port `80` that gives each application a
hostname. `stop` and `restart` work the same way; `restart` also reconciles the edge routes.

Health check:

```bash
curl -s http://localhost:4566/_ministack/health
```

## 2. Bootstrap the emulator

CloudForge templates reference the CDK bootstrap version, as they do on AWS. Bootstrap each region
you plan to deploy to, once:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test
AWS_DEFAULT_REGION=us-east-1 cdk bootstrap aws://000000000000/us-east-1
```

The deploy checks for the bootstrap parameter and prints this command if it is missing.
Bootstrapping does not survive recreating the emulator container, so repeat it after
`emulator restart` creates a fresh one.

Optionally resolve `*.cloudforge.localhost` on systems whose resolver does not do it already
(Windows, older Linux):

```bash
./scripts/setup-cloudforge-local-hosts.sh
```

## 3. Deploy an application

With a deployment context file:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566 AWS_DEFAULT_REGION=us-east-1
cloudforge-cli deploy --context docs/deployment-contexts/Metabase-Stack.json --target ministack
```

A context is a small JSON file:

```json
{"stackName":"Metabase-Stack","applicationId":"metabase","applicationName":"Metabase",
 "environment":"production","runtime":"FARGATE","authMode":"none"}
```

Add `"provisionDatabase": true` to give the application a database, and optionally
`"databaseEngine"` and `"databaseVersion"` (for example `"mysql"` and `"8.4"`). Run
`cloudforge-cli deploy` with no arguments for the interactive wizard, which offers only the engines,
versions, instance sizes and regions the chosen application supports.

The deploy synthesizes the template, adapts it for MiniStack, runs a preflight check, creates any
databases, and deploys the stack as `<stackName>-ministack`. Output is one JSON line per phase.

Several contexts are in [docs/deployment-contexts](docs/deployment-contexts), and
`scripts/deploy-ministack-apps.sh` deploys all of them.

### Open the application

| Address | What it is |
|---|---|
| `http://<application>.cloudforge.localhost/` | Through the edge, for example `jenkins.cloudforge.localhost` |
| `http://nginx.cloudforge.localhost/` | Edge status and the active routes |
| `http://localhost:8888/` | StackPort, a browser for the emulated AWS resources |
| `http://localhost:4566/_alb/<load-balancer-name>/` | Straight to the load balancer, without the edge |

The stack's `MiniStackLocalUrl` output holds the load balancer address.

### Destroy

```bash
cloudforge-cli destroy --context docs/deployment-contexts/Metabase-Stack.json --target ministack
```

This also removes the databases started for the stack.

## Deploy CloudForge Manager

```bash
cloudforge-cli deploy --context docs/deployment-contexts/CloudForgeManager-Stack.json --target ministack
```

The Manager deployment extension builds or pulls the `cloudforgeci/cloudforge-manager` image,
deploys it, reconciles the edge, and waits for `http://manager.cloudforge.localhost/api/v1/health`.
Add `"provisionDatabase": true` to run it on a MySQL (8.0, 8.4) or MariaDB (10.11, 11.4) database instead
of its embedded one; PostgreSQL is not supported for the Manager.

## What the MiniStack adapter changes

MiniStack does not implement every CloudFormation resource. The adapter rewrites the template so the
rest deploys, and the preflight reports what it cannot cover.

- **Databases:** an `AWS::RDS::DBInstance` becomes a real database container started through the RDS
  API, with a stable local password and a short network alias. The template's endpoint references
  point at it.
- **Secrets:** MiniStack drops an ECS container's `Secrets` list. Generated secrets get a stable local
  value and are passed to the container as environment variables.
- **Log retention:** `Custom::LogRetention` resources are removed; they never complete.
- **HTTPS and authentication:** the private CA chain is replaced with plain HTTP, and Cognito users,
  groups and branding that MiniStack lacks are moved to template metadata.
- **File systems:** EFS becomes host bind mounts.
- **Template size:** the body is compacted to fit CloudFormation's 51,200-byte inline limit.

Application ports are not published on the host. The edge routes each hostname to the container.

## Known limits

- **Docker Hub rate limit.** MiniStack pulls images anonymously (100 pulls per hour per IP, and
  `docker login` does not apply to it). If pulls fail with `429`, pull the images yourself first with
  `docker pull`; MiniStack then uses the local copy.
- **amd64-only images on Apple Silicon** (for example Mattermost, OpenCart) need Docker's amd64
  emulation and must be pulled first: `docker pull --platform linux/amd64 <image>`.
- **Applications that are not usable locally:** Drone needs an SCM configuration, and SuiteCRM and
  Magento have no usable image yet.
- **Slow starters.** Several applications take minutes to answer HTTP after the deploy reports
  complete.
- **Aurora and Serverless** are not supported.

## Validation

`validation/fleet.py` deploys a fleet of applications across regions, checks every one and tears the
fleet down:

```bash
python3 validation/fleet.py --regions us-east-1,eu-west-1,ap-southeast-2 --total 30
```

The plan spreads `--total` deployments (30 to 50 is the target) over the regions and over the
applications in `validation/fleet-apps.json`. An application that appears twice in a region gets a
subdomain per instance, so each deployment has its own edge hostname such as
`jenkins-ue1-2.cloudforge.localhost`. The plan runs in waves that fit `--memory-budget-mb`. Each wave is
deployed, checked and destroyed before the next begins.

Per deployment it checks that the deploy completed in the planned region only, that the ECS service
reached its desired count, and that an http application answers on its own hostname with a status below
500. Per wave it checks that each region holds exactly the stacks planned for it, and after the destroy
that no stack, ECS task or database container is left. Results go to `fleet-out/fleet-results.json` and
`fleet-summary.md`, and the exit status is 1 when anything fails.

Needs the AWS CLI and the CDK CLI, and regions are bootstrapped with `cdk bootstrap` when missing. The
`MiniStack fleet` workflow runs it: a nine deployment smoke on pull requests that touch the deployer or
the fleet, the full 30 deployment fleet every Monday, and any size up to 50 on demand.
`validation/run-from-source.sh` runs the installed CLI with this checkout's code, so the workflow tests
the change under review, and `--only-waves 3` reruns a single wave of a plan to reproduce a failure.

Wider coverage lives in cfc-core's `validation/scripts/ministack-app-matrix.py`, which deploys every
registered application, and with `--db-versions` every database engine and version each one lists.

## Troubleshooting

| Symptom | Resolution |
|---|---|
| Deploy says the emulator is not bootstrapped | Run the `cdk bootstrap` command it prints |
| Port `4566` busy | `cloudforge-cli emulator stop --target localstack` |
| Application URL missing or 404 | `cloudforge-cli emulator restart --target ministack` to reconcile the edge |
| Image pull fails with `429` | `docker pull` the image, then redeploy |
| Task never starts | `docker ps -a` and `docker logs` on the `ministack-ecs-*` container |
| Machine is slow or swapping | Lower Docker Desktop memory and CPU; destroy stacks you are not using |

More: [Quick start](docs/guides/LOCAL_EMULATOR_QUICK_START.md),
[emulator edge](docs/guides/LOCAL_EMULATOR_EDGE.md),
[local hostnames](docs/guides/LOCAL_EMULATOR_HOSTS.md).
