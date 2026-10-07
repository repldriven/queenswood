#!/usr/bin/env python3
"""Connect a domain's apex to a static site, and publish to it.

Firebase Hosting, in a project of its own at the organisation. Declared
in web.yml and reconciled by nobody: this is what a person runs
instead. It creates what is absent and deletes nothing, and it never
writes the apex zone -- it reports what apex.yml lacks, and `just
dns-apex-apply` publishes it. See docs/adr/0044.
"""

import gzip
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

import yaml

API = "https://firebasehosting.googleapis.com/v1beta1"
APEX_TTL = 60


def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)


def token():
    result = subprocess.run(["gcloud", "auth", "print-access-token"],
                            capture_output=True, text=True)
    if result.returncode != 0:
        fail(result.stderr.strip() or "gcloud has no access token")
    return result.stdout.strip()


class Hosting:
    def __init__(self, project):
        self.project = project
        self.token = token()

    def call(self, method, url, body=None, data=None, absent_ok=False,
             content_type="application/json"):
        if body is not None:
            data = json.dumps(body).encode()
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Authorization", f"Bearer {self.token}")
        request.add_header("x-goog-user-project", self.project)
        if data is not None:
            request.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(request) as response:
                text = response.read().decode()
        except urllib.error.HTTPError as error:
            # Only an explicit not-found counts as absent: a denial read
            # as an absence creates what somebody else may already hold.
            if error.code == 404 and absent_ok:
                return None
            fail(f"{method} {url}: {error.code}\n{error.read().decode()}")
        return json.loads(text) if text else {}

    def wait(self, operation):
        while not operation.get("done"):
            time.sleep(3)
            operation = self.call("GET", f"{API}/{operation['name']}")
        if "error" in operation:
            fail(json.dumps(operation["error"], indent=2))
        return operation.get("response", {})


def load(path):
    try:
        with open(path) as handle:
            spec = yaml.safe_load(handle)
    except OSError as error:
        fail(f"cannot read {path}: {error.strerror}")

    for field in ("domain", "projectId", "site"):
        if not spec.get(field):
            fail(f"{path} states no {field}")

    domain = spec["domain"].rstrip(".")
    names = spec.get("names") or []
    return {
        "domain": domain,
        "project": spec["projectId"],
        "site": spec["site"],
        "parent": f"projects/{spec['projectId']}/sites/{spec['site']}",
        "redirects": [f"{name}.{domain}" for name in names],
    }


def custom_domain(hosting, s, host):
    return hosting.call("GET", f"{API}/{s['parent']}/customDomains/{host}",
                        absent_ok=True)


def apply(s):
    hosting = Hosting(s["project"])

    site = hosting.call("GET", f"{API}/{s['parent']}", absent_ok=True)
    if site is None:
        hosting.call("POST", f"{API}/projects/{s['project']}/sites"
                     f"?siteId={s['site']}", body={})
        print(f"  site {s['site']} created")
    else:
        print(f"  site {s['site']} exists")

    wanted = [(s["domain"], {})] + [
        (host, {"redirectTarget": s["domain"]}) for host in s["redirects"]
    ]
    for host, body in wanted:
        found = custom_domain(hosting, s, host)
        if found is not None:
            if found.get("redirectTarget", "") != body.get("redirectTarget",
                                                           ""):
                fail(f"{host} redirects to {found.get('redirectTarget')!r} "
                     f"in Hosting, not {body.get('redirectTarget')!r}")
            print(f"  {host} exists")
            continue
        hosting.wait(hosting.call(
            "POST",
            f"{API}/{s['parent']}/customDomains?customDomainId={host}",
            body=body))
        print(f"  {host} created")

    print()
    status(s, None)


def wanted_records(found):
    sets = list((found.get("requiredDnsUpdates") or {}).get("desired", []))
    verification = ((found.get("cert") or {}).get("verification") or {})
    sets += (verification.get("dns") or {}).get("desired", [])
    for record_set in sets:
        for record in record_set.get("records", []):
            yield record


def bare(rdata):
    return rdata[1:-1] if rdata.startswith('"') and rdata.endswith('"') \
        else rdata


def declared(apex_path):
    with open(apex_path) as handle:
        apex = yaml.safe_load(handle)
    domain = apex["domain"].rstrip(".")
    records = {}
    for record in apex.get("records") or []:
        name = record.get("name", "")
        records[(name, record["type"])] = record
    return domain, records


def relative(fqdn, domain):
    fqdn = fqdn.rstrip(".")
    return "" if fqdn == domain else fqdn[:-len(domain) - 1]


def rdata_for(rrtype, rdata):
    if rrtype == "TXT":
        return f'"{bare(rdata)}"'
    if rrtype == "CNAME" and not rdata.endswith("."):
        return rdata + "."
    return rdata


def status(s, apex_path):
    hosting = Hosting(s["project"])
    hosts = [s["domain"]] + s["redirects"]
    wanted = {}

    for host in hosts:
        found = custom_domain(hosting, s, host)
        if found is None:
            print(f"{host}: not connected -- run just web-apply")
            continue
        cert = found.get("cert") or {}
        print(f"{host}: {found.get('hostState')}, "
              f"{found.get('ownershipState')}, {cert.get('state', 'no cert')}")
        for issue in found.get("issues", []) + cert.get("issues", []):
            print(f"  {issue.get('message', issue)}")
        for record in wanted_records(found):
            key = (record["domainName"].rstrip("."), record["type"])
            wanted.setdefault(key, {"ADD": set(), "REMOVE": set()})
            action = "REMOVE" if record.get("requiredAction") == "REMOVE" \
                else "ADD"
            wanted[key][action].add(rdata_for(record["type"],
                                              record["rdata"]))

    if apex_path is None:
        return

    domain, records = declared(apex_path)
    lacking, unwanted = [], []
    for (fqdn, rrtype), actions in sorted(wanted.items()):
        name = relative(fqdn, domain)
        record = records.get((name, rrtype), {})
        have = record.get("rrdatas", [])
        if actions["ADD"] - set(have):
            entry = {"name": name} if name else {}
            entry["type"] = rrtype
            ttl = record.get("ttl", APEX_TTL if rrtype in ("A", "AAAA")
                             and not name else None)
            if ttl:
                entry["ttl"] = ttl
            entry["rrdatas"] = sorted(set(have) | actions["ADD"])
            lacking.append(entry)
        for rdata in actions["REMOVE"] & set(have):
            unwanted.append(f"{fqdn} {rrtype} {rdata}")

    if not lacking and not unwanted:
        print(f"\n{apex_path} carries every record Hosting asks for")
        return
    if lacking:
        print(f"\n{apex_path} lacks these, each shown whole with what it "
              "already holds:\n")
        dumped = yaml.safe_dump(lacking, sort_keys=False, width=200)
        print("\n".join("  " + line for line in dumped.splitlines()))
    if unwanted:
        print(f"\nHosting asks for these to go from {apex_path}:\n")
        print("\n".join("  " + line for line in unwanted))


def files_in(directory):
    for root, dirs, names in os.walk(directory):
        dirs[:] = sorted(d for d in dirs if not d.startswith("."))
        for name in sorted(names):
            if name.startswith("."):
                continue
            path = os.path.join(root, name)
            yield "/" + os.path.relpath(path, directory).replace(os.sep, "/"), \
                path


def publish(s, directory):
    if not os.path.isfile(os.path.join(directory, "index.html")):
        fail(f"{directory} holds no index.html")
    hosting = Hosting(s["project"])

    content = {}
    for url_path, path in files_in(directory):
        with open(path, "rb") as handle:
            packed = gzip.compress(handle.read(), mtime=0)
        content[url_path] = (hashlib.sha256(packed).hexdigest(), packed)

    version = hosting.call("POST", f"{API}/{s['parent']}/versions",
                           body={"config": {}})["name"]
    files = {path: digest for path, (digest, _) in content.items()}
    by_hash = {digest: packed for digest, packed in content.values()}

    keys = sorted(files)
    for start in range(0, len(keys), 1000):
        chunk = {key: files[key] for key in keys[start:start + 1000]}
        populated = hosting.call("POST", f"{API}/{version}:populateFiles",
                                 body={"files": chunk})
        for digest in populated.get("uploadRequiredHashes", []):
            hosting.call("POST", f"{populated['uploadUrl']}/{digest}",
                         data=by_hash[digest],
                         content_type="application/octet-stream")

    hosting.call("PATCH", f"{API}/{version}?updateMask=status",
                 body={"status": "FINALIZED"})
    version_id = version.rsplit("/", 1)[-1]
    hosting.call("POST", f"{API}/{s['parent']}/releases?versionName="
                 f"sites/{s['site']}/versions/{version_id}", body={})
    print(f"  released {len(files)} files from {directory} to {s['site']}")


def main():
    usage = ("usage: web.py apply <web.yml> | status <web.yml> <apex.yml> "
             "| publish <web.yml> <dir>")
    if len(sys.argv) < 3:
        fail(usage)
    action, path = sys.argv[1], sys.argv[2]
    s = load(path)

    if action == "apply" and len(sys.argv) == 3:
        apply(s)
    elif action == "status" and len(sys.argv) == 4:
        status(s, sys.argv[3])
    elif action == "publish" and len(sys.argv) == 4:
        publish(s, sys.argv[3])
    else:
        fail(usage)


if __name__ == "__main__":
    main()
