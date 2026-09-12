TEST_OPTS := "JDK_JAVA_OPTIONS=\"-XX:ActiveProcessorCount=$(n=$(docker info --format '{{.NCPU}}' 2>/dev/null); if [ \"${n:-0}\" -gt 0 ] 2>/dev/null; then echo \"$n\"; else nproc 2>/dev/null || sysctl -n hw.ncpu; fi)\""

# Test the changed bricks in the development project
test:
    {{TEST_OPTS}} clojure -M:poly test project:dev

# Full test suite is the default test plus startup and migrator for all projects
test-all:
    just test
    {{TEST_OPTS}} clojure -M:poly test brick:test-startup:migrator :all

# Parse schemathesis JUnit report; optionally filter by status class: 2xx, 4xx, 5xx (or just 2, 4, 5)
schemathesis-check filter="":
    #!/usr/bin/env python3
    import re
    import sys
    import xml.etree.ElementTree as ET
    from pathlib import Path

    reports = sorted(Path("schemathesis-report").glob("junit-*.xml"))
    if not reports:
        print("No report found — run 'just schemathesis' first.")
        sys.exit(1)
    report = reports[-1]

    raw    = "{{ filter }}"
    prefix = raw[0] if raw else None   # "5xx" -> "5", "4" -> "4", "" -> all
    label  = f" ({raw})" if raw else ""
    print(f"Report: {report.name}  filter: {raw or 'all'}\n")

    failures = []
    for tc in ET.parse(report).getroot().iter("testcase"):
        for child in tc:
            if child.tag not in ("failure", "error"):
                continue
            msg  = (child.get("message") or "") + (child.text or "")
            m    = re.search(r"\[(\d{3})\]", msg)
            code = m.group(1) if m else None
            if prefix is None or (code and code.startswith(prefix)):
                failures.append({
                    "op":   tc.get("name", "?"),
                    "code": f"[{code}]" if code else "[???]",
                    "msg":  msg.strip(),
                })

    if not failures:
        print(f"✅ No failures{label}.")
        sys.exit(0)

    print(f"{'='*60}")
    print(f"  Schemathesis failures{label}: {len(failures)}")
    print(f"{'='*60}\n")
    for f in failures:
        print(f"{f['code']}  {f['op']}")
        detail = next((l.strip() for l in f['msg'].splitlines() if l.strip()), "")
        if detail:
            print(f"       {detail[:120]}")
        print()

# Check test failures from last test run
poly-test-check:
    #!/usr/bin/env python3
    import xml.etree.ElementTree as ET
    import sys
    from pathlib import Path

    xml_file = Path("./target/test-results/junit.xml")

    if not xml_file.exists():
        print("❌ No test results found. Run 'just test' first.")
        sys.exit(1)

    try:
        tree = ET.parse(xml_file)
        root = tree.getroot()

        failures = []
        for testsuite in root.findall('.//testsuite'):
            for testcase in testsuite.findall('.//testcase'):
                failure = testcase.find('failure')
                if failure is not None:
                    failures.append({
                        'package': testsuite.get('package', ''),
                        'test': testcase.get('name', ''),
                        'class': testcase.get('classname', ''),
                        'message': failure.text or ''
                    })

        if failures:
            print("\n=== Failed Tests ===\n")
            for f in failures:
                print(f"❌ {f['package']}/{f['test']}")
                print(f"   {f['message'].strip()[:200]}")
                print()
            print(f"Total failures: {len(failures)}")
        else:
            print("✅ All tests passed!")

    except Exception as e:
        print(f"Error reading test results: {e}")
        sys.exit(1)

# Check dependencies for known CVEs (no args = dev classpath, or pass project name)
nvd project="":
    #!/usr/bin/env zsh
    if [[ -z "{{ project }}" ]]; then
      classpath=$(clojure -Spath -A{{ DOMAIN_ALIASES }}:dev)
    else
      classpath=$(cd projects/{{ project }} && clojure -Spath)
    fi
    # The NVD database defaults to a path under the dependency-check jar in
    # ~/.m2, so it is re-downloaded whenever that dep is bumped; cache it
    # machine-wide instead, without the schema-version subdir so dependency-
    # check can rebuild in place
    data_dir="${XDG_DATA_HOME:-$HOME/.local/share}/dependency-check"
    clojure -J-Dclojure.main.report=stderr -J-Danalyzer.ossindex.enabled=false -J-Ddata.directory="$data_dir" -M:nvd ".config/nvd/nvd-clojure.edn" "$classpath"

# Run schemathesis API fuzzer against the running api
schemathesis base_url="http://127.0.0.1:8080":
    #!/usr/bin/env zsh
    # Requires $ADMIN_TOKEN — a Keycloak-issued service JWT carrying the
    # `admin` realm role. Mint via:
    #   curl -s -d 'grant_type=client_credentials&client_id=queenswood-admin' \
    #     -d "client_secret=$CLIENT_SECRET" \
    #     -d 'scope=queenswood-api-live realm-roles' \
    #     <keycloak-url>/realms/queenswood/protocol/openid-connect/token \
    #     | jq -r .access_token
    set -e

    _post() { curl -s -X POST "$1" -H "Content-Type: application/json" "${@:2}"; }
    _get()  { curl -s "$1" "${@:2}"; }
    _bail() { echo "$1: $2"; exit 1; }
    _id()   { jq -r --arg k "$1" '.[$k] // empty' <<< "$2"; }

    # --- Organisation ---
    echo "Creating organisation..."
    r=$(_post "{{ base_url }}/v1/organizations" \
          -H "Authorization: Bearer $ADMIN_TOKEN" \
          -d '{"name":"Schemathesis Org","status":"test","tier":"micro","currencies":["GBP"]}')
    org_id=$(_id "organization-id" "$r")
    org_token=$(_id "api-key-secret" "$r")
    [[ -z "$org_token" || "$org_token" == null ]] && _bail "org" "$r"

    # --- Parties ---
    echo "Creating parties..."
    r=$(_post "{{ base_url }}/v1/parties" \
          -H "Authorization: Bearer $org_token" \
          -H "Idempotency-Key: $(openssl rand -hex 8)" \
          -d '{"type":"person","display-name":"Schema Thesis","given-name":"Schema","family-name":"Thesis","date-of-birth":"1990-01-01","nationality":"GB","national-identifier":{"type":"national-insurance","value":"TN000001A","issuing-country":"GB"}}')
    party_id=$(_id "party-id" "$r")
    [[ -z "$party_id" || "$party_id" == null ]] && _bail "party 1" "$r"

    r=$(_post "{{ base_url }}/v1/parties" \
          -H "Authorization: Bearer $org_token" \
          -H "Idempotency-Key: $(openssl rand -hex 8)" \
          -d '{"type":"person","display-name":"Schema Thesis Two","given-name":"Schema","family-name":"Thesis Two","date-of-birth":"1991-02-02","nationality":"GB","national-identifier":{"type":"national-insurance","value":"TN000002B","issuing-country":"GB"}}')
    party_id_2=$(_id "party-id" "$r")
    [[ -z "$party_id_2" || "$party_id_2" == null ]] && _bail "party 2" "$r"

    # --- Product ---
    echo "Creating product..."
    r=$(_post "{{ base_url }}/v1/cash-account-products" \
          -H "Authorization: Bearer $org_token" \
          -d '{"name":"Schemathesis Product","product-type":"current","balance-sheet-side":"asset","allowed-currencies":["GBP"],"balance-products":[{"balance-type":"default","balance-status":"posted"}],"allowed-payment-address-schemes":["scan"]}')
    product_id=$(_id "product-id" "$r")
    version_id=$(_id "version-id" "$r")
    [[ -z "$product_id" || "$product_id" == null ]] && _bail "product" "$r"

    echo "Publishing version..."
    _post "{{ base_url }}/v1/cash-account-products/$product_id/versions/$version_id/publish" \
      -H "Authorization: Bearer $org_token" > /dev/null

    # --- Cash accounts ---
    echo "Waiting for parties to activate..."
    sleep 2
    echo "Creating cash accounts..."
    r=$(_post "{{ base_url }}/v1/cash-accounts" \
          -H "Authorization: Bearer $org_token" \
          -H "Idempotency-Key: $(openssl rand -hex 8)" \
          -d "{\"party-id\":\"$party_id\",\"product-id\":\"$product_id\",\"name\":\"Current Account\",\"currency\":\"GBP\"}")
    account_id=$(_id "account-id" "$r")
    [[ -z "$account_id" || "$account_id" == null ]] && _bail "account 1" "$r"

    r=$(_post "{{ base_url }}/v1/cash-accounts" \
          -H "Authorization: Bearer $org_token" \
          -H "Idempotency-Key: $(openssl rand -hex 8)" \
          -d "{\"party-id\":\"$party_id_2\",\"product-id\":\"$product_id\",\"name\":\"Current Account\",\"currency\":\"GBP\"}")
    account_id_2=$(_id "account-id" "$r")
    [[ -z "$account_id_2" || "$account_id_2" == null ]] && _bail "account 2" "$r"

    # --- Balance ---
    echo "Creating balance..."
    _post "{{ base_url }}/v1/cash-accounts/$account_id/balances" \
      -H "Authorization: Bearer $org_token" \
      -d '{"balance-type":"default","balance-status":"posted","currency":"GBP"}' > /dev/null

    # --- Fund account via simulate ---
    echo "Funding account..."
    _post "{{ base_url }}/v1/simulate/organizations/$org_id/inbound-transfer" \
      -H "Authorization: Bearer $ADMIN_TOKEN" \
      -H "Idempotency-Key: $(openssl rand -hex 8)" \
      -d "{\"account-id\":\"$account_id\",\"amount\":1000000,\"currency\":\"GBP\"}" > /dev/null

    # --- Payments ---
    echo "Creating payments..."
    r=$(_post "{{ base_url }}/v1/payments/internal" \
          -H "Authorization: Bearer $org_token" \
          -H "Idempotency-Key: $(openssl rand -hex 8)" \
          -d "{\"debtor-account-id\":\"$account_id\",\"creditor-account-id\":\"$account_id_2\",\"currency\":\"GBP\",\"amount\":1000}")
    internal_payment_id=$(_id "payment-id" "$r")

    r=$(_post "{{ base_url }}/v1/payments/outbound" \
          -H "Authorization: Bearer $org_token" \
          -H "Idempotency-Key: $(openssl rand -hex 8)" \
          -d "{\"debtor-account-id\":\"$account_id\",\"creditor-bban\":\"04000412345678\",\"creditor-name\":\"Schemathesis\",\"currency\":\"GBP\",\"amount\":500,\"scheme\":\"fps\",\"reference\":\"test\"}")
    outbound_payment_id=$(_id "payment-id" "$r")

    # --- Payee check ---
    echo "Creating payee check..."
    r=$(_post "{{ base_url }}/v1/payee-checks" \
          -H "Authorization: Bearer $org_token" \
          -d '{"name":"Arthur Dent","account":{"sort-code":"040062","account-number":"12345678"},"account-type":"personal"}')
    check_id=$(_id "check-id" "$r")

    # --- Policy ---
    echo "Fetching policy..."
    r=$(_get "{{ base_url }}/v1/policies" -H "Authorization: Bearer $org_token")
    policy_id=$(jq -r '.policies[0]."policy-id" // empty' <<< "$r")

    echo "Running schemathesis..."
    ADMIN_TOKEN="$ADMIN_TOKEN" \
    ORG_TOKEN="$org_token" \
    ORG_ID="$org_id" \
    PARTY_ID="$party_id" \
    PRODUCT_ID="$product_id" \
    VERSION_ID="$version_id" \
    ACCOUNT_ID="$account_id" \
    ACCOUNT_ID_2="$account_id_2" \
    INTERNAL_PAYMENT_ID="$internal_payment_id" \
    OUTBOUND_PAYMENT_ID="$outbound_payment_id" \
    BALANCE_TYPE="default" \
    BALANCE_CURRENCY="GBP" \
    BALANCE_STATUS="posted" \
    CHECK_ID="${check_id:-}" \
    POLICY_ID="${policy_id:-}" \
    PYTHONPATH="{{ justfile_directory() }}/scripts" \
    SCHEMATHESIS_HOOKS=schemathesis_hooks \
      uvx schemathesis run "{{ base_url }}/openapi.json" \
        --output-sanitize false \
        --max-examples 500 \
        --phases=examples,coverage,fuzzing \
        --report junit \
        --continue-on-failure
