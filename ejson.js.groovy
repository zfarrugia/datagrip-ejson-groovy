/*
 * MongoDB extractor for DataGrip
 * Save as: ejson.js.groovy
 *   Scratches and Consoles > Extensions > Database Tools and SQL > data > extractors
 */

import static com.intellij.openapi.util.text.StringUtil.escapeStringCharacters as escapeStr

// no 'def' — these must be in the binding to be visible inside methods
NEWLINE = System.getProperty("line.separator")
INDENT = "  "

// shell    -> ISODate("..."), ObjectId("...")   [mongosh syntax, not valid JSON]
// preserve -> { "$date": "..." }                [canonical EJSON]
// plain    -> "..."                             [standard JSON, types stripped]
// typed    -> "ISODate(...)"                    [valid JSON, readable]
MODE = "shell"

ISO = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
ISO.setTimeZone(TimeZone.getTimeZone("UTC"))

def q(Object s) { "\"" + escapeStr(s == null ? "" : s.toString()) + "\"" }

/* ---------- detection ---------- */

def bson(Object o) {
  if (o == null) return null
  def cn = o.getClass().getSimpleName()
  if (cn == "ObjectId")   return [t: "oid", v: o.toString()]
  if (cn == "Decimal128") return [t: "dec", v: o.toString()]
  if (cn == "Binary")     return [t: "bin", v: o.getData().encodeBase64().toString()]
  if (o instanceof java.util.Date)          return [t: "date", v: ISO.format(o)]
  if (o instanceof java.time.Instant)       return [t: "date", v: ISO.format(new Date(o.toEpochMilli()))]
  if (o instanceof java.util.regex.Pattern) return [t: "regex", v: o.pattern(), o: ""]
  if (o instanceof byte[])                  return [t: "bin", v: o.encodeBase64().toString()]
  if (o instanceof Long)                    return [t: "long", v: o.toString()]
  if (o instanceof java.math.BigDecimal)    return [t: "dec", v: o.toString()]
  if (o instanceof String)                  return fromString(o)
  return null
}

def fromString(String s) {
  if (s == null) return null
  def m

  if (s.startsWith("{") && s.indexOf('$') >= 0) {
    m = s =~ /"\$oid"\s*:\s*"([0-9a-fA-F]{24})"/
    if (m.find()) return [t: "oid", v: m.group(1)]
    m = s =~ /"\$date"\s*:\s*\{\s*"\$numberLong"\s*:\s*"(-?\d+)"\s*\}/
    if (m.find()) return [t: "date", v: ISO.format(new Date(m.group(1).toLong()))]
    m = s =~ /"\$date"\s*:\s*"([^"]+)"/
    if (m.find()) return [t: "date", v: m.group(1)]
    m = s =~ /"\$numberLong"\s*:\s*"(-?\d+)"/
    if (m.find()) return [t: "long", v: m.group(1)]
    m = s =~ /"\$numberDouble"\s*:\s*"([^"]+)"/
    if (m.find()) return [t: "dbl", v: m.group(1)]
    m = s =~ /"\$numberDecimal"\s*:\s*"([^"]+)"/
    if (m.find()) return [t: "dec", v: m.group(1)]
    m = s =~ /"base64"\s*:\s*"([^"]*)"/
    if (m.find()) return [t: "bin", v: m.group(1)]
    m = s =~ /"\$regularExpression"[\s\S]*"pattern"\s*:\s*"((?:[^"\\]|\\.)*)"[\s\S]*"options"\s*:\s*"([^"]*)"/
    if (m.find()) return [t: "regex", v: m.group(1), o: m.group(2)]
    m = s =~ /"\$regex"\s*:\s*"((?:[^"\\]|\\.)*)"(?:\s*,\s*"\$options"\s*:\s*"([^"]*)")?/
    if (m.find()) return [t: "regex", v: m.group(1), o: m.group(2) ?: ""]
    return null
  }

  m = s =~ /^ObjectId\(\s*"?([0-9a-fA-F]{24})"?\s*\)$/
  if (m.find()) return [t: "oid", v: m.group(1)]
  m = s =~ /^ISODate\(\s*"?([^")]+)"?\s*\)$/
  if (m.find()) return [t: "date", v: m.group(1)]
  m = s =~ /^NumberLong\(\s*"?(-?\d+)"?\s*\)$/
  if (m.find()) return [t: "long", v: m.group(1)]
  m = s =~ /^NumberDecimal\(\s*"?([^")]+)"?\s*\)$/
  if (m.find()) return [t: "dec", v: m.group(1)]
  return null
}

/* ---------- rendering ---------- */

def renderBson(Map b) {
  if (MODE == "shell") {
    if (b.t == "oid")   return "ObjectId(" + q(b.v) + ")"
    if (b.t == "date")  return "ISODate(" + q(b.v) + ")"
    if (b.t == "long")  return "NumberLong(" + q(b.v) + ")"
    if (b.t == "dbl")   return b.v
    if (b.t == "dec")   return "NumberDecimal(" + q(b.v) + ")"
    if (b.t == "bin")   return "BinData(0," + q(b.v) + ")"
    if (b.t == "regex") return "/" + b.v + "/" + (b.o ?: "")
    return q(b.v)
  }
  if (MODE == "plain") {
    if (b.t == "long" || b.t == "dbl" || b.t == "dec") return b.v
    if (b.t == "regex") return q("/" + b.v + "/" + (b.o ?: ""))
    return q(b.v)
  }
  if (MODE == "typed") {
    if (b.t == "oid")   return q("ObjectId(" + b.v + ")")
    if (b.t == "date")  return q("ISODate(" + b.v + ")")
    if (b.t == "long")  return q("NumberLong(" + b.v + ")")
    if (b.t == "dbl")   return b.v
    if (b.t == "dec")   return q("NumberDecimal(" + b.v + ")")
    if (b.t == "regex") return q("/" + b.v + "/" + (b.o ?: ""))
    if (b.t == "bin")   return q("BinData(" + b.v.length() + " b64)")
    return q(b.v)
  }
  if (b.t == "oid")   return "{ \"\$oid\": " + q(b.v) + " }"
  if (b.t == "date")  return "{ \"\$date\": " + q(b.v) + " }"
  if (b.t == "long")  return "{ \"\$numberLong\": " + q(b.v) + " }"
  if (b.t == "dbl")   return "{ \"\$numberDouble\": " + q(b.v) + " }"
  if (b.t == "dec")   return "{ \"\$numberDecimal\": " + q(b.v) + " }"
  if (b.t == "bin")   return "{ \"\$binary\": { \"base64\": " + q(b.v) + ", \"subType\": \"00\" } }"
  if (b.t == "regex") return "{ \"\$regularExpression\": { \"pattern\": " + q(b.v) + ", \"options\": " + q(b.o ?: "") + " } }"
  return q(b.v)
}

def isScalar(Object v) {
  v == null || v instanceof Number || v instanceof Boolean || v instanceof String
}

def printJSON(int level, Object col, Object o) {
  if (o == null) { OUT.append("null"); return }
  if (o instanceof Tuple) { printJSON(level, o.get(0), o.get(1)); return }

  def b = bson(o)
  if (b != null) { OUT.append(renderBson(b)); return }

  if (o instanceof Map) {
    if (o.isEmpty()) { OUT.append("{}"); return }
    OUT.append("{")
    int i = 0
    o.entrySet().each { entry ->
      OUT.append((i > 0 ? "," : "") + NEWLINE + (INDENT * (level + 1)))
      OUT.append(q(entry.getKey()) + ": ")
      printJSON(level + 1, col, entry.getValue())
      i++
    }
    OUT.append(NEWLINE + (INDENT * level) + "}")
    return
  }

  if (o instanceof Object[] || o instanceof Iterable) {
    def items = (o instanceof Object[]) ? Arrays.asList(o) : o.collect { it }
    if (items.isEmpty()) { OUT.append("[]"); return }
    boolean flat = items.every { isScalar(it) && bson(it) == null }
    OUT.append("[")
    int i = 0
    items.each { item ->
      OUT.append(flat ? (i > 0 ? ", " : "") : ((i > 0 ? "," : "") + NEWLINE + (INDENT * (level + 1))))
      printJSON(level + 1, col, item)
      i++
    }
    OUT.append(flat ? "]" : (NEWLINE + (INDENT * level) + "]"))
    return
  }

  if (o instanceof Boolean) { OUT.append(o.toString()); return }

  def str = FORMATTER.formatValue(o, col)
  def typeName = FORMATTER.getTypeName(o, col)

  def sb = fromString(str)
  if (sb != null) { OUT.append(renderBson(sb)); return }

  boolean isJsonCol = typeName != null &&
      (typeName.equalsIgnoreCase("json") || typeName.equalsIgnoreCase("jsonb"))
  boolean shouldQuote = FORMATTER.isStringLiteral(o, col) && !isJsonCol
  OUT.append(shouldQuote ? q(str) : str)
}

/* ---------- entry point ---------- */

printJSON(0, null, ROWS.transform { row ->
  def map = new LinkedHashMap<String, Object>()
  COLUMNS.each { col ->
    if (row.hasValue(col)) {
      map.put(col.name(), new Tuple(col, row.value(col)))
    }
  }
  map
})
OUT.append(NEWLINE)