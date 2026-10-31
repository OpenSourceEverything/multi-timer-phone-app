# Strict portable JSON in Swift

The companion shares the root [v1 contract](../../../docs/profile-format.md)
and [fixtures](../../../profiles/fixtures/v1/README.md) with Android.
SharedProfile and SharedCue are immutable validated values; a later editor
keeps its draft separately and constructs a new value only after validation.

## Parser choice

The v1 contract rejects duplicate object members and requires original integer
tokens for version/time. Converting JSON directly into an ordinary dictionary
can discard duplicate members before validation sees them. A decoder accepting
an equivalent decimal/exponent would also weaken the interchange contract.

StrictJSON therefore preserves number text and checks duplicate members at
object insertion. This small reader handles only JSON grammar and Unicode;
ProfileBundleCodec separately enforces exact fields/types, format version,
counts, schedule rules and text limits. No third-party parser is introduced.
The parser is isolated from SwiftUI, persistence, network and file access.

The input byte limit is checked before strict UTF-8 decoding. Container depth
is checked before descending into another object/array, with the root at depth
one. Strings reject control characters and unpaired surrogate escapes. Time
values retain Int64 identity and are bounded by the largest exact JavaScript
integer. Text normalization strips only U+0000...U+0020, as Android does.

The writer counts UTF-8 bytes before appending each piece and escapes controls,
quotes and backslashes. Validated model graphs can still exceed the document
limit, so output has its own bound. Encoding a model never creates local IDs
or saves profiles; user confirmation remains a separate operation.

## Verification boundary

Tests use the exact root fixture corpus and generated edge cases for byte,
count, depth, Unicode, integer notation and ordering rules. A semantic round
trip compares every model field, not JSON whitespace or object-key order.

The Swift package builds these same portable sources for fast Linux/macOS
checks. The Xcode scheme also runs the tests in the simulator app host. A codec
pass does not establish UI, file-picker, disk-durability or two-app exchange
acceptance.
