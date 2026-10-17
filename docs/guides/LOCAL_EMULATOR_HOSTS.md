# Local Emulator Hostnames (`*.cloudforge.localhost`)

Readable browser names for CloudForge applications running on **MiniStack** or
**LocalStack**. Both emulators publish ECS task ports on the Docker host (`127.0.0.1`), so
the same names work with either one.

See also: [Local Emulator Quick Start](LOCAL_EMULATOR_QUICK_START.md) · [Local Emulator Edge (nginx)](LOCAL_EMULATOR_EDGE.md) · [MiniStack Setup](https://github.com/CloudForgeCI/cfc-core/blob/develop/docs/ministack/SETUP.md) · [LocalStack README](https://github.com/CloudForgeCI/cfc-core/blob/develop/docs/localstack/README.md)

---

## How It Works

| Fact | Implication |
|------|-------------|
| MiniStack and LocalStack both use gateway port **4566** | Run one emulator at a time |
| Application containers publish ports on the Docker host | Browser traffic goes to `127.0.0.1:<app-port>` |
| A hostname maps to an IP address only | Without the nginx edge, include the port in the URL |

```text
  Browser
     │  http://jenkins.cloudforge.localhost:8080
     ▼
  name resolves to 127.0.0.1
     │
     ▼
  Docker host port (ECS task on MiniStack or LocalStack)
```

With the [nginx edge](LOCAL_EMULATOR_EDGE.md) running, `http://jenkins.cloudforge.localhost/`
works without a port.

You do not need separate names per emulator. When you switch emulators from the platform
menu, the URLs stay the same as long as the applications publish the same host ports.

---

## Hostnames

The canonical names are `<name>.cloudforge.localhost`. The nginx edge routes only these names.

| Hostname | Port | Role |
|----------|------|------|
| `localstack.cloudforge.localhost` | 4566 | LocalStack gateway |
| `ministack.cloudforge.localhost` | 4566 | MiniStack gateway |
| `emulator.cloudforge.localhost` | 4566 | Whichever emulator owns port 4566 |
| `stackport.cloudforge.localhost` | 8888 | StackPort resource browser |
| `nginx.cloudforge.localhost` | 80 | Edge status page |
| `manager.cloudforge.localhost` | 1958 | CloudForge Manager |
| `jenkins.cloudforge.localhost` | 8080 | Jenkins |
| `grafana.cloudforge.localhost` | 3000 | Grafana |
| `prometheus.cloudforge.localhost` | 9090 | Prometheus |
| `vault.cloudforge.localhost` | 8200 | Vault |
| `nexus.cloudforge.localhost` | 8081 | Nexus |
| `sonarqube.cloudforge.localhost` | 9000 | SonarQube |

The example hosts file also defines short names (`jenkins.localhost`, `redis.localhost`,
`postgres.localhost`, `gitea.localhost`, and others). They resolve to `127.0.0.1` but are not
routed by the edge, so use them only with an explicit port, for example
`http://jenkins.localhost:8080`.

Always type `http://`; browsers may treat a bare name as a search. Do not use `*.local`
names: macOS resolves them through mDNS, which causes lookup delays.

---

## Setup

`*.localhost` is reserved for loopback (RFC 6761). macOS and most modern Linux systems resolve
it to `127.0.0.1` without configuration. Windows and older Linux resolvers (without
systemd-resolved or nss-mdns) need hosts entries.

### Option A: helper script

From the repository root:

```bash
./scripts/setup-cloudforge-local-hosts.sh
```

The script writes the marked block from
[`docs/guides/examples/cloudforge.localhost.hosts`](examples/cloudforge.localhost.hosts) into
`/etc/hosts` (using `sudo` when needed). If the edge container is running, it also adds a
second block with the per-instance hostnames the edge currently routes (for example
`jenkins1.cloudforge.localhost`); re-run it after deploying or removing stacks to refresh that
block. Re-running replaces both blocks.

```bash
./scripts/setup-cloudforge-local-hosts.sh --dry-run   # print the blocks without writing
./scripts/setup-cloudforge-local-hosts.sh --remove    # remove both blocks
```

Set `CFC_HOSTS_FILE` to write to a file other than `/etc/hosts`.

### Option B: manual copy

```bash
cat docs/guides/examples/cloudforge.localhost.hosts
sudo sh -c 'cat docs/guides/examples/cloudforge.localhost.hosts >> /etc/hosts'
```

### Windows

Edit `C:\Windows\System32\drivers\etc\hosts` as Administrator and paste the `127.0.0.1` lines
from [`cloudforge.localhost.hosts`](examples/cloudforge.localhost.hosts). Run
`ipconfig /flushdns` if names do not resolve.

### Verify

```bash
ping -c 1 jenkins.cloudforge.localhost    # resolves to 127.0.0.1

# With CloudForge Manager deployed to the emulator
curl -s -o /dev/null -w "%{http_code}\n" http://manager.cloudforge.localhost:1958/api/v1/health
```

---

## Day-to-Day Usage

1. Start one emulator from `InteractiveDeployer --platform` (or `cloudforge-cli emulator start`). This also starts StackPort and the nginx edge.
2. Deploy an application.
3. Open the application through the edge, or directly with its port:

```bash
open "http://jenkins.cloudforge.localhost/"        # through the edge
open "http://jenkins.cloudforge.localhost:8080"    # direct to the host port
```

To find an application's host port, check the stack outputs (`MiniStackApplicationUrl` or
`LocalStackApplicationUrl`), or Docker:

```bash
docker ps --format '{{.Names}}\t{{.Ports}}' | grep -i jenkins
```

### LocalStack Jenkins

LocalStack may run Jenkins with a `--prefix` so that it works behind a path-style ELB URL.
The direct host port (from `docker ps` or `LocalStackApplicationUrl`) and the edge hostname
both work. If the root path returns 404 on the direct port, use the ELB URL from the stack
outputs.

---

## Port Collisions

Several applications use the same container port, for example **3000** (Grafana, Gitea,
Metabase). Hostnames do not change that: only one container can publish a given host port.
Deploy one of those applications at a time. See the
[MiniStack host-port constraint](https://github.com/CloudForgeCI/cfc-core/blob/develop/docs/guides/LOCAL_EMULATOR_APP_CATALOG.md#host-port-constraint).

---

## What Hostnames Do Not Replace

| Still use | Why |
|-----------|-----|
| Stack outputs (`*ApplicationUrl`, `*LocalUrl`) | Authoritative host port and ELB path |
| `AWS_ENDPOINT_URL=http://localhost:4566` | AWS CLI, SDK, and deploy path |
| Route53 records inside the emulator | DNS as CloudFormation defines it; not used by your host resolver |

Hosts entries are a browser convenience. Tests and verification should check CloudFormation
stack outputs, not name resolution.
