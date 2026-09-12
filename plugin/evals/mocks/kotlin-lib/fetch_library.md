---
expect:
  # Guards use a restricted dialect: no groups, alternation, backreferences or lookaround.
  # So "version optional" is expressed as "a colon, then a tail that may contain another".
  coordinate: /^[A-Za-z0-9_.-]+:[A-Za-z0-9_.:+-]+$/
---
{"coordinate":{"group":"io.ktor","artifact":"ktor-client-core","version":"3.5.1"},"resolvedTargets":["common","jvm"],"sourceFileCount":217,"packageCount":20,"fromCache":true}
