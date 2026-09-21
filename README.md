# MongoDB Extractor for DataGrip

A Groovy data extractor for DataGrip / IntelliJ that exports MongoDB query results with BSON types intact, instead of flattening them into meaningless JSON.

DataGrip's built-in JSON extractor turns a Mongo `_id` into a bare string and a date into a timestamp string. Both lose the type, so the output can't be fed back into Mongo. This extractor renders BSON types in whichever form you actually need: mongosh shell syntax, canonical Extended JSON, plain JSON, or readable type hints.

## Install

1. In DataGrip, open **Scratches and Consoles** (left panel).
2. Navigate to **Extensions → Database Tools and SQL → data → extractors**.
3. Drop the script in as `ejson.js.groovy`.

   The filename matters. DataGrip reads it as `<name>.<output extension>.groovy`, so the part before `.groovy` sets the extension of exported files. Use `.js` for shell mode (the output is JavaScript, not JSON) and `.json` for the other three modes.
4. The extractor appears as **ejson** in the dropdown above any result grid, and in **Export Data to File**.

No dependencies. The script deliberately avoids `groovy.json.JsonSlurper`, which isn't on the classpath in DataGrip's scripting sandbox.

## Modes

Set the mode near the top of the script:

```groovy
MODE = "shell"
```

| Mode | Output for a date | Valid JSON | Use it for |
|---|---|---|---|
| `shell` | `ISODate("2026-06-01T03:43:20.080Z")` | No | Pasting into mongosh, seeding scripts, fixtures |
| `preserve` | `{ "$date": "2026-06-01T03:43:20.080Z" }` | Yes | `mongoimport`, driver round-trips, backups |
| `plain` | `"2026-06-01T03:43:20.080Z"` | Yes | Feeding other systems that don't know BSON |
| `typed` | `"ISODate(2026-06-01T03:43:20.080Z)"` | Yes | Eyeballing data while keeping JSON tooling happy |

### shell

```javascript
{
  "_id": ObjectId("507f1f77bcf86cd799439011"),
  "importedAt": ISODate("2026-06-01T03:43:20.080Z"),
  "views": NumberLong("42")
}
```

Runs as-is in mongosh. Not parseable by `JSON.parse` — that's the tradeoff.

### preserve

```json
{
  "_id": { "$oid": "507f1f77bcf86cd799439011" },
  "importedAt": { "$date": "2026-06-01T03:43:20.080Z" },
  "views": { "$numberLong": "42" }
}
```

Canonical Extended JSON v2. Round-trips cleanly through `mongoimport` and every official driver.

### plain

```json
{
  "_id": "507f1f77bcf86cd799439011",
  "importedAt": "2026-06-01T03:43:20.080Z",
  "views": 42
}
```

Type information is gone. Fine as a destination format, not as a backup.

### typed

```json
{
  "_id": "ObjectId(507f1f77bcf86cd799439011)",
  "importedAt": "ISODate(2026-06-01T03:43:20.080Z)",
  "views": "NumberLong(42)"
}
```

Readable and still valid JSON, because the constructor call sits inside a string. If the quotes are the thing bothering you, you want `shell`, not `typed`.

## Supported types

| BSON type | shell | preserve |
|---|---|---|
| ObjectId | `ObjectId("...")` | `{"$oid": "..."}` |
| Date / Instant | `ISODate("...")` | `{"$date": "..."}` |
| Int64 | `NumberLong("...")` | `{"$numberLong": "..."}` |
| Double | bare number | `{"$numberDouble": "..."}` |
| Decimal128 | `NumberDecimal("...")` | `{"$numberDecimal": "..."}` |
| Binary / `byte[]` | `BinData(0, "...")` | `{"$binary": {...}}` |
| Regex | `/pattern/flags` | `{"$regularExpression": {...}}` |

Dates are normalised to UTC as `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`.

## How detection works

Each value gets checked twice.

First by class — the driver usually hands over real objects (`org.bson.types.ObjectId`, `java.util.Date`, `Long`, `Binary`), and those are matched directly.

If that misses, the formatted string is run through `fromString()`, which recognises Extended JSON fragments (`{"$oid": ...}`, both the legacy `{"$date": "..."}` and the canonical `{"$date": {"$numberLong": "..."}}` forms, `$regex` and `$regularExpression`) as well as shell literals (`ObjectId(...)`, `ISODate(...)`). This is why the script still works when DataGrip has already stringified a column before the extractor sees it.

Anything unrecognised falls through to DataGrip's `FORMATTER`, quoted or not according to `isStringLiteral`.

Only values that genuinely are BSON types get wrapped. A field holding base64 text as a plain string stays a plain string — wrapping it in `BinData` would change the document's shape on reimport.

## Customising

**Stop wrapping every `Long`.** In `preserve` mode, all 64-bit integers become `{"$numberLong": ...}`. That's correct canonical EJSON but verbose. To wrap only values that were genuinely int64 in BSON, delete this line from `bson()`:

```groovy
if (o instanceof Long) return [t: "long", v: o.toString()]
```

**Change the date format.** Edit the `ISO` formatter:

```groovy
ISO = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
ISO.setTimeZone(TimeZone.getTimeZone("UTC"))
```

**Add a BSON type.** Two edits: a detection branch in `bson()` or `fromString()` returning `[t: "yourtype", v: "..."]`, and a rendering branch per mode in `renderBson()`.

**Add a mode.** Add a branch at the top of `renderBson()` and set `MODE` to match.

## Gotchas

Config variables are declared **without** `def`. In DataGrip extractors, a script-level `def` is a local variable and is invisible inside method bodies. `MODE`, `NEWLINE`, `INDENT` and `ISO` must go into the script binding, which is why the stock extractor writes `NEWLINE = ...` with no `def`.

Inside GStrings, `$` must be escaped as `\$` or Groovy reads it as interpolation. `"{ \"\$oid\": ..."` is correct; `"{ "$oid": ..."` is a compile error.

The script uses `if/else` rather than `switch` on class literals. Groovy's parser rejects that combination in this context with a misleading `Unexpected input: '{'` error.

Shell mode output is not JSON. Keep the filename as `ejson.js.groovy` so exports land as `.js` and your linters leave them alone.
