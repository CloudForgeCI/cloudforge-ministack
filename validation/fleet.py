#!/usr/bin/env python3
"""Deploys a fleet of applications to MiniStack across regions, checks each one, and tears it down.

    python3 validation/fleet.py --regions us-east-1,eu-west-1,ap-southeast-2 \
        --accounts 000000000000,111111111111,222222222222 --total 30

The fleet is a plan of N deployments spread round-robin over the account and region pairs and over the
applications in fleet-apps.json. MiniStack gives each 12-digit access key its own account, so every
deployment runs with AWS_ACCESS_KEY_ID set to its account and names it in its context's account field. An application that appears more than once in a region gets a subdomain per instance,
so every deployment has its own hostname (for example jenkins-ue1-2.cloudforge.localhost). The plan is
split into waves that fit --memory-budget-mb; each wave is deployed, checked, destroyed and checked
again before the next one starts, so a long plan never needs more memory than one wave.

For every deployment the run checks that
  - the deploy completed, in the account and region the plan chose and in no other,
  - the ECS service reached its desired count,
  - an http application answers on its own edge hostname with a status below 500,
and for every wave that
  - each account and region holds exactly the stacks planned for it,
  - after the destroy no stack, ECS task or database container is left.

Requires MiniStack on localhost:4566 (cloudforge-cli emulator start --target ministack), the AWS CLI and
the CDK CLI on PATH, a CloudForge CLI (FLEET_CLI, default cloudforge-cli) and a way to reconcile the emulator
edge (FLEET_RECONCILE_CMD, default scripts/emulator-edge-reconcile.sh). Each account and region is bootstrapped with `cdk bootstrap` when missing.
Writes fleet-results.json and fleet-summary.md to --out and exits 1 when any check fails.
"""
import argparse
import concurrent.futures
import json
import os
import shlex
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ENDPOINT = os.environ.get("AWS_ENDPOINT_URL", "http://localhost:4566")
DEFAULT_ACCOUNT = "000000000000"
DOMAIN = "example.com"
ENV = {**os.environ, "AWS_ENDPOINT_URL": ENDPOINT, "AWS_ACCESS_KEY_ID": "test", "AWS_SECRET_ACCESS_KEY": "test"}
ENV.pop("AWS_PROFILE", None)
ENV.pop("CFC_DEPLOYING", None)
ENV.pop("CFC_MINISTACK_REPLACE_SAME_APP", None)


def env_for(account):
    """The environment of a process acting in `account`: MiniStack reads the account from the access key."""
    return {**ENV, "AWS_ACCESS_KEY_ID": account if account != DEFAULT_ACCOUNT else "test"}


def region_abbreviation(region):
    """us-east-1 -> ue1, eu-west-1 -> ew1, ap-southeast-2 -> as2."""
    parts = region.split("-")
    return "".join(part[0] for part in parts[:-1]) + parts[-1]


def build_plan(apps, regions, accounts, total):
    """Spreads `total` deployments over the account and region pairs first, then over the applications."""
    pairs = [(account, region) for region in regions for account in accounts]
    seen = {}
    plan = []
    for index in range(total):
        account, region = pairs[index % len(pairs)]
        app = apps[(index // len(pairs)) % len(apps)]
        key = (app["id"], account, region)
        instance = seen.get(key, 0) + 1
        seen[key] = instance
        label = f"{app['id']}-{region_abbreviation(region)}-a{accounts.index(account)}-{instance}"
        plan.append({
            "app": app["id"], "account": account, "region": region, "instance": instance,
            "hostname": f"{label}.cloudforge.localhost", "stackName": f"Fleet-{label}",
            "memoryMb": app["memoryMb"], "expect": app.get("expect", "http"), "path": app.get("path", "/"),
            "database": bool(app.get("database")), "subdomain": label,
        })
    return plan


def split_into_waves(plan, memory_budget_mb, max_wave):
    waves, current, used = [], [], 0
    for item in plan:
        if current and (used + item["memoryMb"] > memory_budget_mb or len(current) >= max_wave):
            waves.append(current)
            current, used = [], 0
        current.append(item)
        used += item["memoryMb"]
    if current:
        waves.append(current)
    return waves


def context_for(item):
    context = {
        "stackName": item["stackName"], "applicationId": item["app"], "applicationName": item["app"],
        "environment": "development", "runtime": "FARGATE", "authMode": "none",
        "region": item["region"], "account": item["account"], "domain": DOMAIN, "subdomain": item["subdomain"],
        # An account other than the default has no placeholder hosted zone, so synthesis would look the
        # zone up in real Route 53. Creating the zone in the emulator avoids that lookup.
        "createZone": item["account"] != DEFAULT_ACCOUNT,
    }
    if item["database"]:
        context["provisionDatabase"] = True
    return context


def aws(account, region, *args, timeout=60):
    """Runs the AWS CLI against the emulator as `account` in `region`; returns parsed JSON or None."""
    try:
        result = subprocess.run(["aws", "--endpoint-url", ENDPOINT, "--region", region, *args, "--output", "json"],
                                capture_output=True, text=True, env=env_for(account), timeout=timeout)
    except subprocess.TimeoutExpired:
        return None
    if result.returncode != 0 or not result.stdout.strip():
        return None
    try:
        return json.loads(result.stdout)
    except ValueError:
        return None


def ensure_bootstrapped(account, region):
    parameter = aws(account, region, "ssm", "get-parameter", "--name", "/cdk-bootstrap/hnb659fds/version")
    if parameter:
        return
    print(f"bootstrapping {account} in {region}", flush=True)
    subprocess.run(["cdk", "bootstrap", f"aws://{account}/{region}"],
                   env={**env_for(account), "AWS_DEFAULT_REGION": region},
                   capture_output=True, text=True, timeout=600, check=True)


def run_cli(verb, context_file, timeout, account):
    try:
        result = subprocess.run([*shlex.split(os.environ.get("FLEET_CLI", "cloudforge-cli")), verb,
                                 "--context", str(context_file), "--target", "ministack"],
                                capture_output=True, text=True, env=env_for(account), timeout=timeout)
        return result.returncode, result.stdout
    except subprocess.TimeoutExpired:
        return 124, ""


def last_phase_status(output, phase):
    status = None
    for line in output.splitlines():
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if record.get("phase") == phase:
            status = record
    return status or {}


def stacks_in(account, region):
    data = aws(account, region, "cloudformation", "list-stacks") or {}
    return {s["StackName"]: s["StackStatus"] for s in data.get("StackSummaries", [])
            if s["StackStatus"] != "DELETE_COMPLETE" and s["StackName"].startswith("Fleet-")}


def deploy_one(item, work, timeout):
    context_file = work / f"{item['stackName']}.json"
    context_file.write_text(json.dumps(context_for(item), indent=2))
    started = time.time()
    code, output = run_cli("deploy", context_file, timeout, item["account"])
    final = last_phase_status(output, "deploy")
    item["deployed"] = final.get("status") == "complete"
    item["deploySeconds"] = round(time.time() - started)
    if not item["deployed"]:
        item["deployError"] = (final.get("message") or output[-300:] or f"exit {code}")[:300].replace("\n", " | ")
    return item


def service_counts(item):
    """(running, desired) of the stack's ECS service in its own region, or None when it is not found."""
    clusters = (aws(item["account"], item["region"], "ecs", "list-clusters") or {}).get("clusterArns", [])
    for cluster in clusters:
        if item["stackName"] not in cluster:
            continue
        services = (aws(item["account"], item["region"], "ecs", "list-services", "--cluster", cluster) or {}).get("serviceArns", [])
        if not services:
            continue
        described = (aws(item["account"], item["region"], "ecs", "describe-services", "--cluster", cluster, "--services", *services)
                     or {}).get("services", [])
        if described:
            return described[0].get("runningCount", 0), described[0].get("desiredCount", 0)
    return None


def http_status(item):
    request = urllib.request.Request("http://127.0.0.1" + item["path"], headers={"Host": item["hostname"]})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except Exception as error:  # connection refused, timeout, ...
        return type(error).__name__


def reconcile_edge(command):
    subprocess.run(command, shell=True, capture_output=True, text=True, env=ENV, timeout=300)


def check_wave(wave, reconcile_command, settle_seconds):
    """Waits for the tasks, reconciles the edge, then checks service counts and hostnames."""
    deadline = time.time() + settle_seconds
    pending = [item for item in wave if item["deployed"]]
    while pending and time.time() < deadline:
        for item in list(pending):
            counts = service_counts(item)
            if counts and counts[1] > 0 and counts[0] >= counts[1]:
                item["ecsRunning"] = True
                pending.remove(item)
        if pending:
            time.sleep(10)
    for item in wave:
        item.setdefault("ecsRunning", False)
    reconcile_edge(reconcile_command)
    deadline = time.time() + settle_seconds
    waiting = [item for item in wave if item["deployed"] and item["ecsRunning"] and item["expect"] == "http"]
    while waiting and time.time() < deadline:
        for item in list(waiting):
            status = http_status(item)
            item["httpStatus"] = status
            if isinstance(status, int) and status < 500:
                waiting.remove(item)
        if waiting:
            time.sleep(10)
            reconcile_edge(reconcile_command)


def check_regions(wave, pairs):
    """Each account and region must hold exactly the stacks the wave planned for it."""
    problems = []
    for account, region in pairs:
        expected = {item["stackName"] + "-ministack" for item in wave
                    if item["account"] == account and item["region"] == region and item["deployed"]}
        actual = set(stacks_in(account, region))
        if actual != expected:
            problems.append({"account": account, "region": region,
                             "missing": sorted(expected - actual), "unexpected": sorted(actual - expected)})
    return problems


def destroy_one(item, work, timeout):
    code, output = run_cli("destroy", work / f"{item['stackName']}.json", timeout, item["account"])
    item["destroyed"] = last_phase_status(output, "destroy").get("status") == "complete"
    if not item["destroyed"]:
        item["destroyError"] = f"exit {code}: " + output.strip()[-400:]
    return item


def leaked_containers():
    result = subprocess.run(["docker", "ps", "-a", "--format", "{{.Names}}"], capture_output=True, text=True)
    return sorted(name for name in result.stdout.split()
                  if name.startswith(("ministack-ecs-", "ministack-rds-")))


def remove_containers(names):
    if names:
        subprocess.run(["docker", "rm", "-f", *names], capture_output=True, text=True)


def verdict(item):
    failures = []
    if not item["deployed"]:
        failures.append("deploy failed: " + item.get("deployError", ""))
    elif not item["ecsRunning"]:
        failures.append("ECS service did not reach its desired count")
    elif item["expect"] == "http" and not (isinstance(item.get("httpStatus"), int) and item["httpStatus"] < 500):
        failures.append(f"{item['hostname']}{item['path']} answered {item.get('httpStatus')}")
    if item.get("destroyed") is False:
        failures.append("destroy failed: " + item.get("destroyError", ""))
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--regions", default="us-east-1,eu-west-1,ap-southeast-2")
    parser.add_argument("--accounts", default=DEFAULT_ACCOUNT,
                        help="comma-separated 12-digit account ids; MiniStack reads the account from the access key")
    parser.add_argument("--total", type=int, default=30, help="deployments in the whole plan (30 to 50 is the target)")
    parser.add_argument("--apps", help="comma-separated application ids from fleet-apps.json (default: all)")
    parser.add_argument("--memory-budget-mb", type=int, default=6000, help="memory one wave may use")
    parser.add_argument("--max-wave", type=int, default=10)
    parser.add_argument("--parallel", type=int, default=2, help="deploys and destroys running at once")
    parser.add_argument("--settle-seconds", type=int, default=240)
    parser.add_argument("--deploy-timeout", type=int, default=900)
    parser.add_argument("--out", default=str(HERE / "fleet-out"))
    parser.add_argument("--plan-only", action="store_true", help="print the plan and waves, deploy nothing")
    parser.add_argument("--keep", action="store_true", help="leave the last wave deployed for inspection")
    parser.add_argument("--only-waves", help="comma-separated wave numbers of the plan to run, for reproducing a failure")
    parser.add_argument("--prefetch-images", action="store_true", help="docker pull the images of the planned applications, then exit")
    args = parser.parse_args()

    regions = args.regions.split(",")
    accounts = args.accounts.split(",")
    for account in accounts:
        if not (len(account) == 12 and account.isdigit()):
            parser.error(f"account {account!r} is not 12 digits")
    pairs = [(account, region) for region in regions for account in accounts]
    apps = json.loads((HERE / "fleet-apps.json").read_text())["apps"]
    if args.apps:
        wanted = args.apps.split(",")
        apps = [app for app in apps if app["id"] in wanted]
    plan = build_plan(apps, regions, accounts, args.total)
    waves = split_into_waves(plan, args.memory_budget_mb, args.max_wave)
    print(f"{len(plan)} deployments, {len(accounts)} accounts, {len(regions)} regions, {len(waves)} waves", flush=True)
    selected = {int(n) for n in args.only_waves.split(",")} if args.only_waves else None
    if args.prefetch_images:
        images = sorted({image for app in apps for image in app.get("images", [])})
        for image in images:
            for attempt in range(3):
                if subprocess.run(["docker", "pull", image], capture_output=True).returncode == 0:
                    break
                time.sleep(20 * (attempt + 1))
            else:
                print(f"could not pull {image}", file=sys.stderr)
                return 1
        print(f"pulled {len(images)} images")
        return 0
    if args.plan_only:
        for number, wave in enumerate(waves, 1):
            print(f"wave {number} ({sum(i['memoryMb'] for i in wave)} MB): "
                  + ", ".join(f"{i['stackName']}@{i['account']}/{i['region']}" for i in wave))
        return 0

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    reconcile_command = os.environ.get("FLEET_RECONCILE_CMD", str(HERE.parent / "scripts" / "emulator-edge-reconcile.sh"))
    for account, region in pairs:
        ensure_bootstrapped(account, region)

    results, wave_problems = [], []
    with tempfile.TemporaryDirectory() as temp:
        work = Path(temp)
        for number, wave in enumerate(waves, 1):
            if selected and number not in selected:
                continue
            started = time.time()
            print(f"wave {number}/{len(waves)}: deploying {len(wave)}", flush=True)
            with concurrent.futures.ThreadPoolExecutor(args.parallel) as pool:
                list(pool.map(lambda item: deploy_one(item, work, args.deploy_timeout), wave))
            check_wave(wave, reconcile_command, args.settle_seconds)
            regional = check_regions(wave, pairs)
            if regional:
                wave_problems.append({"wave": number, "stage": "deployed", "regions": regional})
            if args.keep and number == (max(selected) if selected else len(waves)):
                results.extend(wave)
                break
            with concurrent.futures.ThreadPoolExecutor(args.parallel) as pool:
                list(pool.map(lambda item: destroy_one(item, work, args.deploy_timeout), wave))
            leftovers = {f"{account}/{region}": sorted(stacks_in(account, region))
                         for account, region in pairs if stacks_in(account, region)}
            containers = leaked_containers()
            if leftovers or containers:
                wave_problems.append({"wave": number, "stage": "destroyed", "stacks": leftovers, "containers": containers})
                remove_containers(containers)
            results.extend(wave)
            print(f"wave {number}: {sum(1 for i in wave if not verdict(i))}/{len(wave)} passed "
                  f"in {round(time.time() - started)}s", flush=True)

    failed = [item for item in results if verdict(item)]
    (out / "fleet-results.json").write_text(json.dumps({"plan": results, "waveProblems": wave_problems}, indent=2))
    lines = [f"# MiniStack fleet: {len(results) - len(failed)}/{len(results)} deployments passed", "",
             f"Accounts: {', '.join(accounts)}. Regions: {', '.join(regions)}. Waves: {len(waves)}.", "",
             "| stack | account | region | app | deploy | ECS | http | notes |", "|---|---|---|---|---|---|---|---|"]
    for item in results:
        lines.append(f"| {item['stackName']} | {item['account']} | {item['region']} | {item['app']} | "
                     f"{'ok' if item['deployed'] else 'FAILED'} ({item.get('deploySeconds', '-')}s) | "
                     f"{'running' if item['ecsRunning'] else 'not running'} | {item.get('httpStatus', '-')} | "
                     f"{'; '.join(verdict(item))} |")
    if wave_problems:
        lines += ["", "## Wave problems", "", "```", json.dumps(wave_problems, indent=2), "```"]
    (out / "fleet-summary.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines[:3]))
    return 1 if failed or wave_problems else 0


if __name__ == "__main__":
    sys.exit(main())
