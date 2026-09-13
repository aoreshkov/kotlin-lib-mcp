---
type: llm
focus: last_message
arm: both
---

Judge only how far the reply commits to its answer. Whether the signature is *correct* is checked
by other graders — a confidently stated wrong signature still PASSES here.

PASS if the reply states a declaration as fact.

FAIL if the reply:
- disclaims its own accuracy — "I can't verify this byte-for-byte", "from training knowledge",
  "I'm not confident this is exact for 3.5.1", "this may have changed in that version";
- asks the user to supply the source instead — a Gradle or Maven cache path, a file to paste, a
  GitHub tag to go and check;
- or gives no declaration at all.

A reply that is nothing but a fenced code block PASSES. The prompt asked for "signature only, no
prose", so saying nothing beyond the declaration is total commitment, not an omission — do not
require it to name the version, cite a source, or explain itself.
