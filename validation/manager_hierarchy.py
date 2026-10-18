#!/usr/bin/env python3
"""Deploys a hierarchy of CloudForge Managers on MiniStack and deploys through each level.

    python3 validation/manager_hierarchy.py --image-tag hier-jvm

  1. A hub Manager is deployed to account 000000000000 with the CloudForge CLI.
  2. On the hub an admin and a manager user are created. The manager user is granted deploy:create.
  3. The hub's admin links account 111111111111 and the hub's manager user links account 222222222222, each the
     way a customer does: the hub returns a CloudFormation template and an external id, the template is run in
     the account (here against MiniStack with that account's access key), and the role it creates is attached and
     validated.
  4. Through those links the hub admin deploys a Manager into 111111111111 and the hub's manager user deploys one
     into 222222222222.
  5. On each of those spoke Managers an admin and a manager user are created. Each links the Manager's own
     account, and both deploy an application into it.

Checks, with the account and the user named in each failure:
  - every Manager answers, and reports the account it runs in,
  - every link validates as ACTIVE,
  - every deploy through a Manager succeeds and its stack exists in the target account and in no other account,
  - the stacks are gone after the teardown.

MiniStack gives each 12-digit access key, and each ECS task role, its own account, and a Manager in an emulator
account is not restricted by a license (LicenseValidationService), so no license is needed.

Requires MiniStack on localhost:4566, the AWS CLI and the CDK CLI, a CloudForge CLI (FLEET_CLI, default
cloudforge-cli), a way to reconcile the emulator edge (FLEET_RECONCILE_CMD) and a Manager image tagged
cloudforgeci/cloudforge-manager:<image-tag> that contains the cross-account allowance for MiniStack. Writes
hierarchy-results.json to --out and exits 1 when any check fails.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import fleet
from fleet import DEFAULT_ACCOUNT, aws, ensure_bootstrapped, env_for, reconcile_edge, run_cli

DOMAIN = "example.com"
PASSWORD = "password123"
EDGE = "http://{host}.cloudforge.localhost"


class Failures:
    def __init__(self):
        self.items = []

    def check(self, condition, message):
        print(("  ok    " if condition else "  FAIL  ") + message, flush=True)
        if not condition:
            self.items.append(message)
        return condition


class Manager:
    """A deployed Manager reached through the emulator edge, with one session per user."""

    def __init__(self, name, host, account):
        self.name, self.host, self.account = name, host, account
        self.cookies = {}
        self.subjects = {}

    def url(self, path):
        return EDGE.format(host=self.host) + path

    def request(self, user, method, path, body=None, timeout=120):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.url(path), data=data, method=method)
        request.add_header("Content-Type", "application/json")
        if user in self.cookies:
            request.add_header("Cookie", self.cookies[user])
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                self.remember(user, response)
                text = response.read().decode()
                return response.status, (json.loads(text) if text.strip().startswith(("{", "[")) else text)
        except urllib.error.HTTPError as error:
            text = error.read().decode()
            try:
                return error.code, json.loads(text)
            except ValueError:
                return error.code, text

    def remember(self, user, response):
        cookie = response.headers.get("Set-Cookie")
        if cookie:
            self.cookies[user] = cookie.split(";", 1)[0]

    def wait_healthy(self, reconcile, seconds=240):
        """Waits for the health endpoint, reconciling the edge as it goes: the route exists only once the task runs."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            status, _ = self.request("anonymous", "GET", "/api/v1/health", timeout=10)
            if status == 200:
                return True
            reconcile_edge(reconcile)
            time.sleep(5)
        return False


def ensure_synth_service():
    """MiniStack runs a task's containers apart, so a Manager cannot reach the synth sidecar of its own task on
    localhost. The Manager image used here is pointed at this standalone container instead."""
    import subprocess
    status = subprocess.run(["docker", "inspect", "--format", "{{.State.Status}}", "cfc-synth"],
                            capture_output=True, text=True).stdout.strip()
    if not status:
        subprocess.run(["docker", "run", "-d", "--name", "cfc-synth", "--network", "cfc-network",
                        "cloudforgeci/cloudforge-synth-service:latest"], capture_output=True, text=True, check=True)
    else:
        if status != "running":
            subprocess.run(["docker", "start", "cfc-synth"], capture_output=True, text=True)
        subprocess.run(["docker", "network", "connect", "cfc-network", "cfc-synth"], capture_output=True, text=True)


def deploy_with_cli(name, context, work, account, args):
    """Deploys a Manager with the CloudForge CLI, which is how a first Manager gets onto an account."""
    path = work / f"{name}.json"
    path.write_text(json.dumps(context))
    code, output = run_cli("deploy", path, args.deploy_timeout, account)
    return code == 0 and '"status":"complete"' in output.replace(" ", ""), output


def manager_context(name, subdomain, account, region, args):
    return {
        "stackName": name, "applicationId": "cloudforge-manager", "applicationName": "CloudForge Manager",
        "environment": "development", "runtime": "FARGATE", "authMode": "none", "region": region,
        "account": account, "domain": DOMAIN, "subdomain": subdomain,
        # An account other than the default has no placeholder hosted zone to look up.
        "createZone": account != DEFAULT_ACCOUNT, "containerImage": args.image_tag,
    }


def app_context(name, app, subdomain, region):
    return {
        "stackName": name, "applicationId": app, "applicationName": app, "environment": "development",
        "runtime": "FARGATE", "authMode": "none", "region": region, "domain": DOMAIN,
        "subdomain": subdomain, "createZone": True,
    }


def create_users(manager, failures):
    """First-run admin, then a manager user whom the admin lets deploy custom infrastructure."""
    status, body = manager.request("admin", "POST", "/api/v1/auth/setup",
                                   {"username": "admin", "password": PASSWORD})
    if not failures.check(status == 200, f"{manager.name}: first-run admin created (HTTP {status})"):
        return False
    status, body = manager.request("admin", "POST", "/api/v1/users",
                                   {"username": "mgr", "password": PASSWORD, "role": "manager"})
    if not failures.check(status in (200, 201), f"{manager.name}: manager user created (HTTP {status})"):
        return False
    subject = body.get("subject")
    # deploy:create is the "full DeploymentContext freedom" policy; a manager does not hold it by default.
    status, _ = manager.request("admin", "PUT", f"/api/v1/users/{subject}/policies",
                                {"policies": [{"policy": "deploy:create", "effect": "allow"}]})
    failures.check(status == 200, f"{manager.name}: admin granted deploy:create to the manager user (HTTP {status})")
    status, _ = manager.request("mgr", "POST", "/api/v1/auth/login", {"username": "mgr", "password": PASSWORD})
    return failures.check(status == 200, f"{manager.name}: manager user signed in (HTTP {status})")


def link_account(manager, user, alias, account, region, work, failures):
    """Links `account` the way a customer does: run the Manager's template in the account, attach the role."""
    label = f"{manager.name}/{user}: link {account}"
    status, created = manager.request(user, "POST", "/api/v1/accounts",
                                      {"alias": alias, "awsAccountId": account, "region": region})
    if not failures.check(status == 201, f"{label}: connection created (HTTP {status} {created if status != 201 else ''})"):
        return None
    template = work / f"{manager.name}-{user}-{account}.template.json"
    template.write_text(created["template"])
    stack = f"CfcAccess-{manager.name}-{user}"
    code = aws_cli(account, region, "cloudformation", "create-stack", "--stack-name", stack,
                   "--template-body", f"file://{template}", "--capabilities", "CAPABILITY_NAMED_IAM",
                   "--parameters", f"ParameterKey=ManagerPrincipalArn,ParameterValue={created['managerPrincipalArn']}",
                   f"ParameterKey=ExternalId,ParameterValue={created['externalId']}")
    if not failures.check(code == 0, f"{label}: access role stack created in {account}"):
        return None
    role_arn = wait_for_output(account, region, stack, "RoleArn")
    if not failures.check(role_arn is not None, f"{label}: access role created ({role_arn})"):
        return None
    status, _ = manager.request(user, "PATCH", f"/api/v1/accounts/{created['id']}", {"roleArn": role_arn})
    failures.check(status == 200, f"{label}: role attached (HTTP {status})")
    status, validated = manager.request(user, "POST", f"/api/v1/accounts/{created['id']}/validate", {})
    state = validated.get("status") if isinstance(validated, dict) else validated
    if not failures.check(status == 200 and state == "ACTIVE", f"{label}: validated, status {state} (HTTP {status})"):
        return None
    return created["id"]


def aws_cli(account, region, *args):
    import subprocess
    result = subprocess.run(["aws", "--endpoint-url", fleet.ENDPOINT, "--region", region, *args],
                            capture_output=True, text=True, env=env_for(account), timeout=120)
    if result.returncode:
        print(result.stderr.strip()[-300:], flush=True)
    return result.returncode


def wait_for_output(account, region, stack, key, seconds=120):
    deadline = time.time() + seconds
    while time.time() < deadline:
        described = aws(account, region, "cloudformation", "describe-stacks", "--stack-name", stack)
        if described:
            current = described["Stacks"][0]
            if current["StackStatus"] == "CREATE_COMPLETE":
                for output in current.get("Outputs", []):
                    if output["OutputKey"] == key:
                        return output["OutputValue"]
            if current["StackStatus"].endswith("FAILED") or current["StackStatus"].startswith("ROLLBACK"):
                return None
        time.sleep(2)
    return None


def deploy_through(manager, user, connection_id, context, failures, seconds=900):
    """Deploys through a Manager into the account a connection links; returns the journal entry."""
    label = f"{manager.name}/{user}: deploy {context['stackName']}"
    status, started = manager.request(
        user, "POST", f"/api/v1/deployments?target=ministack&accountConnectionId={connection_id}", context)
    if not failures.check(status == 202, f"{label}: accepted (HTTP {status} {started if status != 202 else ''})"):
        return None
    operation = started["operationId"]
    deadline = time.time() + seconds
    entry = {}
    while time.time() < deadline:
        status, entry = manager.request(user, "GET", f"/api/v1/journal/{operation}")
        if status == 200 and entry.get("status") != "RUNNING":
            break
        time.sleep(3)
    ok = isinstance(entry, dict) and entry.get("status") == "SUCCEEDED"
    failures.check(ok, f"{label}: finished {entry.get('status') if isinstance(entry, dict) else entry} "
                       f"{'' if ok else json.dumps(entry)[:400]}")
    return entry if ok else None


def stacks_everywhere(accounts, region):
    found = {}
    for account in accounts:
        found[account] = {s["StackName"] for s in (aws(account, region, "cloudformation", "list-stacks") or
                                                  {"StackSummaries": []})["StackSummaries"]
                          if s["StackStatus"] != "DELETE_COMPLETE"}
    return found


def check_only_in(failures, found, stack, account, label):
    holders = [a for a, names in found.items() if stack in names]
    failures.check(holders == [account], f"{label}: {stack} exists in {holders or 'no account'}, expected only {account}")


def run(args):
    failures = Failures()
    work = Path(args.out)
    work.mkdir(parents=True, exist_ok=True)
    region = args.region
    spokes = args.spoke_accounts.split(",")
    accounts = [DEFAULT_ACCOUNT, *spokes]
    reconcile = fleet.os.environ.get("FLEET_RECONCILE_CMD", "scripts/emulator-edge-reconcile.sh")
    created_stacks = []  # (account, stack) in creation order, removed in reverse

    for account in accounts:
        ensure_bootstrapped(account, region)
    ensure_synth_service()

    print("== hub", flush=True)
    hub = Manager("HubManager", "manager-hub", DEFAULT_ACCOUNT)
    ok, output = deploy_with_cli(hub.name, manager_context("HubManager", hub.host, DEFAULT_ACCOUNT, region, args),
                                 work, DEFAULT_ACCOUNT, args)
    if not failures.check(ok, f"hub deployed with the CLI {'' if ok else output[-300:]}"):
        return failures, created_stacks
    created_stacks.append((DEFAULT_ACCOUNT, "HubManager-ministack"))
    reconcile_edge(reconcile)
    if not failures.check(hub.wait_healthy(reconcile), "hub answers on its hostname"):
        return failures, created_stacks
    if not create_users(hub, failures):
        return failures, created_stacks
    status, self_view = hub.request("admin", "GET", "/api/v1/accounts/self")
    failures.check(status == 200 and self_view.get("accountId") == DEFAULT_ACCOUNT
                   and self_view.get("crossAccountDeployEntitled") is True,
                   f"hub runs in {DEFAULT_ACCOUNT} and may link accounts: {self_view}")

    print("== hub links the spoke accounts and deploys a Manager into each", flush=True)
    actors = ["admin", "mgr"]
    spoke_managers = []
    for index, account in enumerate(spokes):
        user = actors[index % 2]
        link = link_account(hub, user, f"spoke-{account}", account, region, work, failures)
        created_stacks.append((account, f"CfcAccess-{hub.name}-{user}"))
        if link is None:
            continue
        name = f"SpokeManager{index + 1}"
        host = f"manager-s{index + 1}"
        entry = deploy_through(hub, user, link, manager_context(name, host, account, region, args), failures)
        created_stacks.append((account, f"{name}-ministack"))
        if entry is None:
            continue
        spoke_managers.append(Manager(name, host, account))
    reconcile_edge(reconcile)
    found = stacks_everywhere(accounts, region)
    for manager in spoke_managers:
        check_only_in(failures, found, f"{manager.name}-ministack", manager.account, "hub deploy")

    print("== each spoke Manager links its own account and deploys an application", flush=True)
    for manager in spoke_managers:
        if not failures.check(manager.wait_healthy(reconcile), f"{manager.name} answers on its hostname"):
            continue
        if not create_users(manager, failures):
            continue
        status, view = manager.request("admin", "GET", "/api/v1/accounts/self")
        failures.check(status == 200 and view.get("accountId") == manager.account,
                       f"{manager.name} runs in {manager.account}: {view}")
        for user, app in (("admin", "gitea"), ("mgr", "grafana")):
            link = link_account(manager, user, f"self-{user}", manager.account, region, work, failures)
            created_stacks.append((manager.account, f"CfcAccess-{manager.name}-{user}"))
            if link is None:
                continue
            stack = f"{manager.name}-{app}"
            entry = deploy_through(manager, user, link,
                                   app_context(stack, app, f"{manager.name.lower()}-{app}", region), failures)
            created_stacks.append((manager.account, f"{stack}-ministack"))
            if entry is not None:
                found = stacks_everywhere(accounts, region)
                check_only_in(failures, found, f"{stack}-ministack", manager.account,
                              f"{manager.name}/{user}")
    return failures, created_stacks


def teardown(created_stacks, region, failures):
    print("== teardown", flush=True)
    for account, stack in reversed(created_stacks):
        aws_cli(account, region, "cloudformation", "delete-stack", "--stack-name", stack)
    deadline = time.time() + 300
    while time.time() < deadline:
        left = [(a, s) for a, s in created_stacks
                if s in {x["StackName"] for x in (aws(a, region, "cloudformation", "list-stacks") or
                                                   {"StackSummaries": []})["StackSummaries"]
                         if x["StackStatus"] != "DELETE_COMPLETE"}]
        if not left:
            break
        time.sleep(5)
    failures.check(not left, f"every stack deleted ({left})")
    fleet.remove_containers(fleet.leaked_containers())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image-tag", default="hier-jvm",
                        help="tag of cloudforgeci/cloudforge-manager to deploy as every Manager")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--spoke-accounts", default="111111111111,222222222222")
    parser.add_argument("--deploy-timeout", type=int, default=900)
    parser.add_argument("--out", default="hierarchy-out")
    parser.add_argument("--keep", action="store_true", help="leave everything deployed")
    args = parser.parse_args()

    failures, created = run(args)
    if not args.keep:
        teardown(created, args.region, failures)
    Path(args.out, "hierarchy-results.json").write_text(json.dumps({"failures": failures.items}, indent=2))
    print(f"\n{'PASSED' if not failures.items else str(len(failures.items)) + ' CHECK(S) FAILED'}")
    return 1 if failures.items else 0


if __name__ == "__main__":
    sys.exit(main())
