package com.assistant.database

/**
 * Schema-driven NL→SQL interpreter. All table/column knowledge is derived
 * from the live database schema — zero hardcoded table/column/dept names.
 *
 * Patterns:
 *   "show tables" → SHOW TABLES
 *   "show <table>" → SELECT * FROM <table>
 *   "find/count <thing> in/with <condition>" → filtered SELECT / COUNT
 *   "join <t1> and <t2>" → auto-join via FK inference
 *   "average/sum/min/max <field> by <group>" → aggregation
 *   "custom <raw SQL>" → raw passthrough
 *   "<field> <op> <value>" → bare condition query
 */
class SqlPromptInterpreter(
    private val schemaProvider: () -> List<DatabaseManager.TableInfo>,
    private val valueQuery: (String, String) -> List<String>
) {
    data class Interpretation(val sql: String, val explanation: String)
    private data class JoinInfo(val groupExpr: String, val fromClause: String, val groupLabel: String)
    private data class FieldRef(val table: String, val column: String)
    private data class Cond(val sql: String, val label: String)

    private val schema: List<DatabaseManager.TableInfo> by lazy { schemaProvider() }
    private val tableIndex: Map<String, DatabaseManager.TableInfo> by lazy { schema.associateBy { it.name.lowercase() } }
    private val columnIndex: Map<String, List<FieldRef>> by lazy {
        schema.flatMap { t -> t.columns.map { c -> c.name.lowercase() to FieldRef(t.name, c.name) } }
            .groupBy({ it.first }) { it.second }
    }
    private val columnSynonyms: Map<String, String> by lazy { buildColumnSynonyms() }
    private val knownValues: Map<String, List<FieldRef>> by lazy {
        schema.flatMap { t ->
            t.columns.filter { isText(it.type) }.flatMap { c ->
                valueQuery(t.name, c.name).map { it.lowercase().trim() to FieldRef(t.name, c.name) }
            }
        }.groupBy({ it.first }) { it.second }
    }

    /** Lookup a table by any casing. */
    private fun tableInfo(name: String) = tableIndex[name.trim().lowercase()]

    /** Entry point: try sub-interpreters in priority order, fall back to DUNNO. */
    fun interpret(prompt: String): Interpretation {
        val clean = prompt.trim().lowercase().replace(RE_WS, " ")
        if (clean.startsWith("select")) return Interpretation(prompt, "Raw SQL passthrough")
        if (clean in SHOW_TABLES) return Interpretation("SHOW TABLES", "Showing all available tables")
        if (clean in HELP_WORDS) return Interpretation("",
            "Available tables: ${schema.joinToString(", ") { it.name }}. Try: show <table>, find <table> with <condition>")
        return show(clean) ?: bareTable(clean) ?: describe(clean)
            ?: tableCond(clean, RE_COUNT, "COUNT(*) AS count")
            ?: tableCond(clean, RE_FIND, "*")
            ?: aggregation(clean)
            ?: tableCond(clean, RE_TABLE_WHERE, "*")
            ?: join(clean) ?: bareCondition(clean) ?: DUNNO
    }

    // ---------- Sub-interpreters, chain helpers (non-null = matched) ----------

    /** Match "show|display|list <table>" → SELECT *. */
    private fun show(clean: String) = matchResolve(RE_SHOW, clean) { selectAll(it) }

    /** Match bare table name → SELECT *. */
    private fun bareTable(clean: String) = resolveTable(clean)?.let { selectAll(it) }

    /** Match "describe|structure <table>" → INFORMATION_SCHEMA query. */
    private fun describe(clean: String) = matchResolve(RE_DESC, clean) { describeTable(it) }

    /** Match "count/find <thing> [in/with <condition>]" → filtered SELECT or COUNT. */
    private fun tableCond(clean: String, re: Regex, selectExpr: String): Interpretation? = re.find(clean)?.let { m ->
        val tbl = resolveTable(m.groupValues[1]) ?: return@let null
        val cond = m.groupValues[2].trim()
        if (cond.isNotEmpty()) return condSql(cond, tbl)?.let { sql ->
            Interpretation("SELECT $selectExpr FROM $tbl WHERE $sql", "Finding rows in $tbl")
        } ?: DUNNO
        if (selectExpr != "*") return Interpretation("SELECT $selectExpr FROM $tbl", "Counting rows in $tbl")
        selectAll(tbl)
    }

    /** Match "min/max/avg/sum <field> [by <group>]" → ORDER BY LIMIT 1 or grouped aggregation. */
    private fun aggregation(clean: String): Interpretation? = RE_AGG.find(clean)?.let { m ->
        val aggWord = m.groupValues[1].lowercase()
        val rawField = m.groupValues[2].trim()
        val rawGroupBy = m.groupValues[3].trim()
        val fn = AGG_MAP[aggWord] ?: "AVG"

        if (rawGroupBy.isNotEmpty()) {
            if (!isKnownField(rawField) || !isKnownField(rawGroupBy)) return DUNNO
            return aggGrouped(rawGroupBy, rawField, fn, aggWord)
        }

        if (!isKnownField(rawField) && rawField.split(RE_WS).none { isKnownField(it) }) return DUNNO
        val resolved = resolveFieldFrom(rawField, m, clean) ?: return DUNNO
        val order = if (aggWord in ASC_WORDS) "ASC" else "DESC"
        Interpretation("SELECT * FROM ${resolved.table} ORDER BY ${resolved.column} $order LIMIT 1",
            "Finding row with ${if (order == "ASC") "lowest" else "highest"} ${resolved.column}")
    }

    /** When the agg field might be prefixed by a table name ("product with lowest stock"), peel it off. */
    private fun resolveFieldFrom(rawField: String, m: MatchResult, clean: String): FieldRef? {
        val contextTable = clean.substring(0, m.range.first).trim()
            .split(RE_WS).firstNotNullOfOrNull { resolveTable(it) }
        val resolved = rawField.split(RE_WS).lastOrNull { resolveTable(it) != null }
            ?.let { resolveField(it) } ?: resolveField(rawField) ?: return null
        if (contextTable != null && !resolved.table.equals(contextTable, ignoreCase = true)) return null
        return resolved
    }

    /** Build a grouped aggregation SQL — handles same-table and cross-table via FK join. Returns null = not understood. */
    private fun aggGrouped(groupBy: String, field: String, fn: String, aggWord: String): Interpretation? {
        val g = resolveField(groupBy) ?: return null; val a = resolveField(field) ?: return null
        val aAlias = a.table.take(1).lowercase()
        val info = if (g.table.equals(a.table, ignoreCase = true)) JoinInfo("$aAlias.${g.column}", "FROM ${a.table} $aAlias", g.column)
        else resolveJoinableGroup(g.table, a.table, aAlias) ?: return null
        val alias = "${fn}_${a.column}"
        return Interpretation("SELECT ${info.groupExpr} AS group_name, CAST($fn($aAlias.${a.column}) AS INT) AS $alias ${info.fromClause} GROUP BY ${info.groupExpr} ORDER BY $alias DESC",
            "${aggWord.replaceFirstChar(Char::titlecase)} ${a.column} grouped by ${info.groupLabel}")
    }

    /** Build group expression/from-clause/label for cross-table aggregation. Returns null if tables can't be joined. */
    private fun resolveJoinableGroup(gTable: String, aTable: String, aAlias: String): JoinInfo? =
        tableInfo(gTable)?.let { gInfo -> tableInfo(aTable)?.let { aInfo ->
            resolveForeignKeyFromTo(aInfo, gInfo)?.let { (fkCol, refCol) ->
                val gCol = gInfo.columns.firstOrNull { isText(it.type) }?.name ?: refCol
                JoinInfo("$gTable.$gCol", "FROM $aTable $aAlias JOIN $gTable ON $aAlias.$fkCol = $gTable.$refCol", "$gTable.$gCol")
            }
        } }

    /** Match "join <t1> and <t2>" → JOIN via FK or cross-join. */
    private fun join(clean: String): Interpretation? = RE_JOIN.find(clean)?.let { m ->
        resolveTable(m.groupValues[1])?.let { t1 -> resolveTable(m.groupValues[2])?.let { t2 -> buildJoin(t1, t2) } }
    }

    /** Match "<field> <op> <value>" with English operators (equals/is/like). */
    private fun bareCondition(clean: String): Interpretation? = RE_BARE_COND.find(clean)?.let { m ->
        resolveField(m.groupValues[1])?.let { (table, col) ->
            val op = m.groupValues[2]; val value = m.groupValues[3].trim()
            val sqlOp = when (op) { "equals", "is" -> "="; "like" -> "LIKE"; else -> op }
            val sqlVal = quoteVal(value)
            Interpretation("SELECT * FROM $table WHERE ${cmpField(col, sqlVal)} $sqlOp $sqlVal",
                "Finding rows where $col $op $value")
        }
    }

    // --- SQL builders ---

    /** SELECT * FROM <table>. */
    private fun selectAll(table: String) = Interpretation("SELECT * FROM $table", "Selecting all rows from $table")

    /** INFORMATION_SCHEMA query for table structure. */
    private fun describeTable(table: String) = Interpretation(
        "SELECT COLUMN_NAME AS column, DATA_TYPE AS type FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '$table'",
        "Showing structure of $table")

    // --- Resolvers ---

    /** Match a regex capturing a table name, resolve it, then map to Interpretation. */
    private fun matchResolve(re: Regex, input: String, f: (String) -> Interpretation) =
        re.find(input)?.let { m -> resolveTable(m.groupValues[1])?.let { f(it) } }

    /** Resolve a condition string to its SQL WHERE clause, or null. */
    private fun condSql(condition: String, table: String) = resolveCondition(condition, table)?.sql

    /** Resolve a column name to a canonical column name, or null if unknown. */
    private fun resolveColumn(name: String) = if (isKnownField(name)) resolveField(name)?.column else null

    /** Resolve a table name (with singular/plural inflection) to canonical name, or null. */
    private fun resolveTable(name: String): String? {
        val key = name.trim().lowercase()
        return tableInfo(key)?.name ?: tableInfo("${key}s")?.name ?: run {
            val singular = key.removeSuffix("es").removeSuffix("s")
            val alt = if (key.endsWith("ies")) key.removeSuffix("ies") + "y" else null
            (if (singular != key) tableInfo(singular)?.name else null) ?: alt?.let { tableInfo(it)?.name }
        }
    }

    /** Resolve "field" → (canonicalTable, canonicalColumn) by exact match, table→numeric-col, synonym, or prefix match. */
    private fun resolveField(name: String): FieldRef? {
        val key = name.trim().lowercase()
        columnIndex[key]?.let { return it.first() }
        resolveTable(key)?.let { t -> return pickNumericCol(t) }
        columnSynonyms[key]?.let { colName -> columnIndex[colName.lowercase()]?.let { return it.first() } }
        return columnIndex.entries.firstOrNull { (cn, _) -> cn.startsWith(key) || key.startsWith(cn) }?.value?.first()
    }

    /** When a table name is given as field, pick its best numeric column. */
    private fun pickNumericCol(tableName: String): FieldRef {
        val info = tableInfo(tableName)!!
        val num = info.columns.filter { isNum(it.type) }
            .firstOrNull { !it.name.equals("id", ignoreCase = true) && !it.name.lowercase().endsWith("_id") }
            ?: info.columns.firstOrNull { isNum(it.type) }
        return FieldRef(info.name, num?.name ?: info.columns.first().name)
    }

    /**
     * Parse a condition into (sqlClause, displayText), or null if not understood.
     * Tries: explicit operator, implicit equals, known value (with FK subquery), then bare text fallback.
     */
    private fun resolveCondition(condition: String, table: String): Cond? {
        val clean = condition.trim().lowercase()
        fun condFieldValue(field: String, value: String, op: String = "=") =
            resolveColumn(field.trim())?.let { col -> Cond("${cmpField(col, quoteVal(value.trim()))} $op ${quoteVal(value.trim())}", "$col $op $value") }

        RE_OP.find(clean)?.let { return condFieldValue(it.groupValues[1], it.groupValues[3], it.groupValues[2]) }
        RE_BARE_EQ_OR_TEXT.find(clean)?.let { return condFieldValue(it.groupValues[1], it.groupValues[2]) }

        /**
         * Build a condition SQL from a known value entry.
         * Handles cross-table FK subquery, self-referencing FK, and plain column equality.
         */
        return knownValues[clean]?.let { known ->
            val (tbl, col) = known.firstOrNull { it.table == table.lowercase() } ?: known.first()
            val display = "$col = '$clean'"
            if (tbl != table.lowercase()) {
                tableInfo(table)?.let { t -> tableInfo(tbl)?.let { resolveForeignKeyFromTo(t, it) } }
                    ?.let { (fk, ref) -> Cond("$fk = (SELECT $ref FROM $tbl WHERE LOWER($col) = '$clean')", display) }
            } else resolveForeignKey(tbl, col)?.let { (refTable, refCol, fkCol) ->
                Cond("$fkCol = (SELECT $refCol FROM $refTable WHERE LOWER($refCol) = '$clean')", display)
            } ?: Cond("$col = '${clean.replace("'", "''")}'", display)
        } ?: resolveBareWord(clean, table)
    }

    /** Single-word condition fallback: match any text column in the table. */
    private fun resolveBareWord(clean: String, table: String): Cond? {
        if (clean.contains(" ")) return null
        return tableInfo(table)?.columns?.firstOrNull { isText(it.type) }
            ?.let { Cond("LOWER(${it.name}) = '$clean'", clean) }
    }

    /** Resolve a column ending in _id → (referencedTable, referencedIdCol, fkCol). */
    private fun resolveForeignKey(table: String, column: String): Triple<String, String, String>? {
        val lower = column.lowercase()
        val refName = when {
            lower.endsWith("_id") -> lower.removeSuffix("_id")
            lower.endsWith("id") && lower.length > 2 -> lower.removeSuffix("id")
            else -> return null
        }
        return (tableInfo(refName) ?: tableInfo("${refName}s"))?.let { refTable ->
            val idCol = refTable.columns.firstOrNull { it.name.lowercase() == "id" } ?: refTable.columns.first()
            Triple(refTable.name, idCol.name, column)
        }
    }

    /** Build a JOIN interpretation between two tables, trying both FK directions. */
    private fun buildJoin(t1: String, t2: String): Interpretation? =
        tableInfo(t1)?.let { t1Info -> tableInfo(t2)?.let { t2Info ->
            buildJoinFk(t1Info, t2Info, "a", t1, t2)
                ?: buildJoinFk(t2Info, t1Info, "b", t2, t1)
                ?: Interpretation("SELECT * FROM $t1, $t2", "Cross-joining $t1 and $t2 (no FK found)")
        } }

    /** Build a JOIN SQL when `from` has an FK referencing `to`, or null. */
    private fun buildJoinFk(from: DatabaseManager.TableInfo, to: DatabaseManager.TableInfo, alias: String, fromName: String, toName: String): Interpretation? =
        resolveForeignKeyFromTo(from, to)?.let { (fkCol, refCol) ->
            val toCols = to.columns.filter { it.name.lowercase() != refCol.lowercase() }
                .joinToString(", ") { "$toName.${it.name} AS ${toName.lowercase()}_${it.name.lowercase()}" }
            Interpretation("SELECT $alias.*, $toCols FROM $fromName $alias JOIN $toName ON $alias.$fkCol = $toName.$refCol",
                "Joining $fromName and $toName on $fkCol")
        }

    /** Check whether a field name is known as a column, table, or synonym. */
    private fun isKnownField(name: String): Boolean {
        val key = name.trim().lowercase()
        return columnIndex.containsKey(key) || resolveTable(key) != null || columnSynonyms.containsKey(key)
    }

    /** Find an FK pair where `from` has a column ending in `_id` matching `to`. */
    private fun resolveForeignKeyFromTo(from: DatabaseManager.TableInfo, to: DatabaseManager.TableInfo): Pair<String, String>? {
        val toSingular = to.name.lowercase().removeSuffix("es").removeSuffix("s")
        return from.columns.firstOrNull { col ->
            val lower = col.name.lowercase()
            lower.endsWith("_id") && lower.removeSuffix("_id") in listOf(toSingular, to.name.lowercase())
        }?.let { col -> col.name to (to.columns.firstOrNull { it.name.lowercase() == "id" }?.name ?: to.columns.first().name) }
    }

    /** Build a map of column name → canonical name, including stripped suffixes and hardcoded aliases. */
    private fun buildColumnSynonyms(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (table in schema) for (col in table.columns) {
            val lower = col.name.lowercase()
            map[lower] = col.name
            val stripped = lower.removeSuffix("_id").removeSuffix("_fk")
            if (stripped != lower) map[stripped] = col.name
            map[lower.replace("_", " ")] = col.name
            map[lower.replace("_", "")] = col.name
        }
        map.putAll(COLUMN_ALIASES)
        return map
    }

    companion object {
        private val RE_WS = Regex("\\s+")
        private val RE_BARE_WORD = Regex("^\\w+$")
        private val RE_SHOW = Regex("(?:show|display|list|all|select|zobraz|vsechny)\\s+(.+?)(?:\\s+table)?$")
        private val RE_DESC = Regex("(?:describe|structure|schema|info)\\s+(.+?)(?:\\s+table)?$")
        private val RE_COUNT = Regex("(?:how many|count|pocet|kolik)\\s+(.+?)(?:\\s+in\\s+(.+))?$")
        private val RE_FIND = Regex("(?:find|search|select|get|where|najdi|vyhledej|uka?z)\\s+(.+?)(?:\\s+(?:in|from|where|with|that|kde|s|v|ve|z)\\s+(.+))?$")
        private val AGG_GROUPS = mapOf(
            "AVG" to listOf("average", "avg", "prumer"),
            "SUM" to listOf("sum", "soucet", "total"),
            "MIN" to listOf("min", "minimum", "nejmensi", "nejlevnejsi", "most cheap", "cheapest", "lowest"),
            "MAX" to listOf("max", "maximum", "nejvetsi", "nejdrazsi", "most expensive", "top", "highest"))
        private val AGG_MAP = AGG_GROUPS.flatMap { (fn, words) -> words.map { it to fn } }.toMap()
        private val RE_AGG = Regex("(" + AGG_MAP.keys.joinToString("|") { Regex.escape(it) } + ")\\s+(.+?)(?:\\s+(?:by|per|podle|dle)\\s+(.+))?$")
        private val RE_TABLE_WHERE = Regex("^(\\w+)\\s+(?:in|from|v|z|with|s|kde|where)\\s+(.+)$")
        private val RE_JOIN = Regex("(?:join|spoj)\\s+(\\w+)\\s+(?:and|with|a|s)\\s+(\\w+)")
        private val RE_BARE_COND = Regex("^(\\w+)\\s*(>|<|>=|<=|=|!=|equals|is|like)\\s*(.+)$")
        private val RE_OP = Regex("^(\\w+)\\s*(>|<|>=|<=|=|!=)\\s*(.+)$")
        private val RE_BARE_EQ_OR_TEXT = Regex("^(\\w+)\\s+(.+)$")
        private val SHOW_TABLES = setOf("show tables", "list tables", "tables")
        private val HELP_WORDS = setOf("help", "prompts", "examples", "commands", "napoveda", "priklady")
        private val COLUMN_ALIASES = mapOf(
            "wage" to "salary", "income" to "salary", "plat" to "salary",
            "jmeno" to "name", "oddeleni" to "department_id",
            "cena" to "price", "cost" to "price",
            "skladem" to "stock", "quantity" to "stock",
            "kategorie" to "category",
            "misto" to "location", "city" to "location",
            "nastup" to "hire_date", "datum" to "date")
        private val ASC_WORDS = AGG_MAP.filterValues { it == "MIN" }.keys
        private val DUNNO = Interpretation("", "I didn't understand")

        private fun quoteVal(v: String): String { val t = v.trim(); return if (t.toDoubleOrNull() != null) t else "'${t.replace("'", "''")}'" }
        private fun isQuoted(v: String) = v.startsWith("'")
        private fun cmpField(f: String, v: String) = if (isQuoted(v)) "LOWER($f)" else f
        private fun isText(type: String) = type.lowercase().let { it.contains("varchar") || it.contains("char") || it.contains("text") }
        private fun isNum(type: String) = type.lowercase().let { it.contains("int") || it.contains("decimal") || it.contains("numeric") || it.contains("float") || it.contains("double") || it.contains("real") }
    }
}
