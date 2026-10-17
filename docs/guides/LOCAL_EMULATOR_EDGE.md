# Local Emulator Edge (nginx)

The emulator edge gives MiniStack and LocalStack applications browser URLs without a port
number. An nginx container (`cfc-emulator-edge`) listens on port 80 and routes requests by
`Host` header (`*.cloudforge.localhost`) to the host ports that the emulated ECS tasks publish
on the Docker host.

See also: [Local hostnames](LOCAL_EMULATOR_HOSTS.md) · [Local Emulator Quick Start](LOCAL_EMULATOR_QUICK_START.md) · [StackPort](https://github.com/CloudForgeCI/cfc-core/blob/develop/docs/localstack/README.md#resource-browser-stackport)

The edge only proxies HTTP. To browse CloudFormation stacks, ECS services, and other
emulated resources, use StackPort on port 8888.

---

## Architecture

```text
  http://jenkins.cloudforge.localhost/          (no port)
            │
            ▼
  name resolves to 127.0.0.1
            │
            ▼
  cfc-emulator-edge (nginx :80)
            │  proxy_pass to the published host port
            ▼
  ECS task container on the Docker host (MiniStack or LocalStack)
```

**Prerequisites**

1. `*.cloudforge.localhost` resolves to `127.0.0.1`. macOS and most modern Linux resolvers
   do this without configuration; otherwise run `./scripts/setup-cloudforge-local-hosts.sh`
   (see [Local hostnames](LOCAL_EMULATOR_HOSTS.md)).
2. One emulator running on port 4566.
3. Docker, with host port 80 free (or `CFC_EDGE_HTTP_PORT` set).

---

## Quick Start

Starting an emulator from the Interactive Deployer platform menu (or `cloudforge-cli emulator
start`) also starts its companions: StackPort on port 8888 and the nginx edge on port 80.
After a MiniStack or LocalStack deploy, CloudForge reconciles the edge routes automatically.

```bash
cloudforge-cli emulator start --target ministack   # or --target localstack
# Deploy applications with cloudforge-cli deploy, or the Interactive Deployer.

open "http://nginx.cloudforge.localhost/"       # edge status and active routes
open "http://stackport.cloudforge.localhost/"
open "http://localstack.cloudforge.localhost/"  # or ministack.cloudforge.localhost
open "http://jenkins.cloudforge.localhost/"     # after a Jenkins deploy
```

The platform menu actions are `start`, `stop`, `restart`, `status`, and `reconcile_edge`.
Stopping the last running emulator also stops the edge.

### Environment Variables

| Variable | Default | Effect |
|----------|---------|--------|
| `CFC_EMULATOR_COMPANIONS` | `true` | `false` skips both StackPort and the edge when an emulator starts |
| `CFC_STACKPORT_AUTOSTART` | `true` | `false` skips StackPort only |
| `CFC_EDGE_AUTOSTART` | `true` | `false` skips the edge, including the reconcile after deploy |
| `CFC_EDGE_HTTP_PORT` | `80` | Host port for the edge, for example `8088` |

### Edge Scripts

The scripts in `scripts/` run `com.cloudforge.core.local.EmulatorEdgeCli` (implemented in
cfc-core's `cloudforge-core` module, resolved here as an ordinary Maven dependency) through
`exec-maven-plugin` and can be run from any directory. They require a prior `mvn install` /
dependency resolution.

| Script | Action |
|--------|--------|
| `emulator-edge-start.sh` | Create and start `cfc-emulator-edge` |
| `emulator-edge-stop.sh` | Remove the edge container |
| `emulator-edge-restart.sh` | Stop, then start |
| `emulator-edge-rebuild.sh` | Stop, pull the nginx image, start |
| `emulator-edge-status.sh` | Show whether the edge is running and healthy, and its routes |
| `emulator-edge-reconcile.sh` | Rewrite the virtual hosts from the host ports currently published |
| `emulator-edge-reload.sh` | Run `nginx -s reload` in the container |

You can also run the CLI directly. Unlike cfc-core (where `cloudforge-core` is a sibling
reactor module reached with `-pl cloudforge-core`), this repo resolves it as a plain
`<dependency>` of its own single-module build, so the goal runs against this repo's own root
module with no `-pl` flag:

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.cloudforge.core.local.EmulatorEdgeCli \
  -Dexec.args=status -Dexec.classpathScope=runtime
```

Generated nginx configuration is written to a `.emulator-edge/` directory (gitignored). The
base configuration is `docker/emulator-edge/nginx.conf`.

---

## How Reconcile Maps Applications

Reconcile reads `docker ps`, takes each published port, and maps the container port to a
hostname. For containers deployed with a `subdomain`, the hostname from the container's
`CFC_LOCALSTACK_EDGE_HOSTNAME` variable takes precedence, so two instances of the same
application get separate hostnames.

| Container port | Hostname |
|----------------|----------|
| 4566 (emulator gateway) | `localstack.cloudforge.localhost` or `ministack.cloudforge.localhost`, plus `emulator.cloudforge.localhost` |
| 8080 on the StackPort container | `stackport.cloudforge.localhost` |
| 1958 | `manager.cloudforge.localhost` |
| 8080 | `jenkins.cloudforge.localhost` |
| 3000 | `grafana.cloudforge.localhost`, or `metabase` / `gitea` by container name |
| 80 | `gitlab.cloudforge.localhost`, or the CMS or Drone hostname by container name (for example `wordpress`, `drupal`, `magento`, `drone`) |
| 8065 | `mattermost.cloudforge.localhost` |
| 9090 | `prometheus.cloudforge.localhost` |
| 8200 | `vault.cloudforge.localhost` |
| 8081 | `nexus.cloudforge.localhost` |
| 9000 | `sonarqube.cloudforge.localhost` |
| 6379 | `redis.cloudforge.localhost` |
| 5432 | `postgres.cloudforge.localhost` |

`nginx.cloudforge.localhost` is served by the edge itself and lists the active routes.
Requests for any other hostname receive a 404.

The edge serves only the `*.cloudforge.localhost` names. Short names such as
`jenkins.localhost` reach the application only with an explicit port.

Route53 records created in the emulator for stacks with a `domain` or `subdomain` are not
used by the edge; use the `*.cloudforge.localhost` names.

---

## LocalStack Jenkins

When LocalStack adapts Jenkins to run behind a path-style ELB URL (`--prefix`), the edge
proxies to the task's host port and adds the prefix for requests to `/`, so
`http://jenkins.cloudforge.localhost/` works without the ELB path.

---

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Connection refused on port 80 | `./scripts/emulator-edge-status.sh`; confirm Docker is running |
| 502 Bad Gateway | The backend port is down. Check `docker ps`, then reconcile |
| 404 "application route not found" | No route for that hostname. Check `http://nginx.cloudforge.localhost/`, then reconcile |
| Wrong application on a hostname | Two applications publish the same port (for example 3000). Stop one stack |
| Name does not resolve | Run `./scripts/setup-cloudforge-local-hosts.sh` |
| Port 80 already in use on macOS | Another process (for example AirPlay Receiver) is bound to port 80. Disable it or set `CFC_EDGE_HTTP_PORT=8088` |

---

## Limitations

- HTTP only; no TLS.
- ALB OIDC and Cognito authentication are not emulated.
- It does not replace StackPort or CloudForge Manager.
- It does not resolve host-port collisions between applications.
