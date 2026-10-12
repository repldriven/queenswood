<script>
  import { Card, CardHeader, CardBody, CardFooter } from "@queenswood/ui";
</script>

<div class="pv-stack">
  <div class="pv-grid" style="grid-template-columns:repeat(3,minmax(0,1fr))">
    <Card href="#cards">
      <CardHeader kicker="ADR-0013" title="One unified API. OpenAPI is the contract." />
      <CardBody><p>Bank-shaped, not implementation-shaped. The spec drives client generation, validation, and documentation.</p></CardBody>
      <CardFooter><a href="#cards">single-unified-api →</a></CardFooter>
    </Card>
    <Card href="#cards">
      <CardHeader kicker="policy-evaluation" title="Policies as data, not hard-coded rules." />
      <CardBody><p>Capabilities and limits are records. A curative-permit pattern lets a tenant self-correct out of breach.</p></CardBody>
      <CardFooter><a href="#cards">policy-evaluation →</a></CardFooter>
    </Card>
    <Card variant="feature" href="#cards">
      <CardHeader kicker="REPL · TESTCONTAINERS" title="REPL on the inside." />
      <CardBody><p>Start a REPL, evaluate a comment block, and the whole system boots inside Testcontainers.</p></CardBody>
      <CardFooter><a href="#cards">just repl →</a></CardFooter>
    </Card>
  </div>
  <div class="pv-grid" style="grid-template-columns:repeat(2,minmax(0,1fr))">
    <Card variant="sunk" padding="compact">
      <CardHeader kicker="SUNK" title="A recessed area." />
      <CardBody><p>Surface-sunk background. For inset content.</p></CardBody>
    </Card>
    <Card variant="outline" padding="compact">
      <CardHeader kicker="OUTLINE" title="A hairline frame." />
      <CardBody><p>Transparent fill, just the rule.</p></CardBody>
    </Card>
  </div>
</div>
