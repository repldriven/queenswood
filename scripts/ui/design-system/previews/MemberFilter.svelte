<script>
  import { MemberFilter } from "@queenswood/ui";
  const MEMBERS = [
    { "user-id": "usr.01", name: "Ada Lovelace", email: "ada@northwind.example" },
    { "user-id": "usr.02", name: "Alex Morgan", email: "alex@northwind.example" },
    { "user-id": "usr.03", name: "", email: "grace@northwind.example" },
  ];
  let selected = $state(["usr.02"]);
</script>

<div style="max-width:260px">
  <MemberFilter members={MEMBERS} {selected} onChange={(ids) => (selected = ids)} />
</div>
