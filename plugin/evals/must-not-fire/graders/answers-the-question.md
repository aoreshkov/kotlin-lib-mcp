---
type: llm
focus: last_message
---

PASS if the reply explains the difference between `flatMap` and `map(...).flat()` in
JavaScript/TypeScript terms — for example that `flatMap` flattens exactly one level, that it
is equivalent to `map` followed by `flat(1)`, or that it is a single pass rather than two.

FAIL if the reply is about Kotlin, Java or a JVM library; if it asks for a Maven coordinate;
if it says it cannot answer without looking the API up; or if it does not address the
difference at all.
