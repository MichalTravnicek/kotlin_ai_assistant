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

    private val schema: List<DatabaseManager.TableInfo> by lazy { schemaProvider() }

    private val tableIndex: Map<String, DatabaseManager.TableInfo> by lazy {
        schema.associateBy { it.name.lowercase() }
    }

    private val columnIndex: Map<String, List<Pair<String, String>>> by lazy {
        schema.flatMap { t -> t.columns.map { c -> c.name.lowercase() to (t.name to c.name) } }
            .groupBy({ it.first }) { it.second }
    }

    private val columnSynonyms: Map<String, String> by lazy { buildColumnSynonyms() }

    private val knownValues: Map<String, List<Pair<String, String>>> by lazy {
        val map = mutableMapOf<String, MutableList<Pair<String, String>>>()
        for (table in schema) {
            for (col in table.columns) {
                if (isText(col.type)) {
                    for (value in valueQuery(table.name, col.name)) {
                        map.getOrPut(value.lowercase().trim()) { mutableListOf() }
                            .add(table.name to col.name)
                    }
                }
            }
        }
        map
    }

    fun interpret(prompt: String): Interpretation {
        val clean = prompt.trim().lowercase().replace(RE_WS, " ")

        if (clean.startsWith("select")) return Interpretation(prompt, "Raw SQL passthrough")
        if (clean in SHOW_TABLES) return Interpretation("SHOW TABLES", "Showing all available tables")
        if (clean in HELP_WORDS) {
            return Interpretation(
                "",
                "Available tables: ${schema.joinToString(", ") { it.name }}. " +
                        "Try: show <table>, find <table> with <condition>"
            )
        }

        show(clean)?.let { return it }
        if (clean.matches(RE_BARE_WORD)) bareTable(clean)?.let { return it }
        describe(clean)?.let { return it }
        count(clean)?.let { return it }
        find(clean)?.let { return it }
        aggregation(clean)?.let { return it }
        tableWhere(clean)?.let { return it }
        join(clean)?.let { return it }
        bareCondition(clean)?.let { return it }

        return DUNNO
    }

    // ---------- sub-interpreters (return null = doesn't match) ----------

    private fun show(clean: String): Interpretation? {
        RE_SHOW.find(clean)?.let { m ->
            resolveTable(m.groupValues[1])?.let { return selectAll(it) }
        }
        return null
    }

    private fun bareTable(clean: String): Interpretation? {
        resolveTable(clean)?.let { return selectAll(it) }
        return null
    }

    private fun describe(clean: String): Interpretation? {
        RE_DESC.find(clean)?.let { m ->
            resolveTable(m.groupValues[1])?.let { return describeTable(it) }
        }
        return null
    }

    private fun count(clean: String): Interpretation? {
        RE_COUNT.find(clean)?.let { m ->
            val tbl = resolveTable(m.groupValues[1]) ?: return@let null
            val cond = m.groupValues[2].trim()
            return if (cond.isNotEmpty()) {
                condSql(cond, tbl)?.let { sql ->
                    Interpretation("SELECT COUNT(*) AS count FROM $tbl WHERE $sql", "Counting filtered rows in $tbl")
                } ?: DUNNO
            } else {
                Interpretation("SELECT COUNT(*) AS count FROM $tbl", "Counting all rows in $tbl")
            }
        }
        return null
    }

    private fun find(clean: String): Interpretation? {
        RE_FIND.find(clean)?.let { m ->
            val tbl = resolveTable(m.groupValues[1]) ?: return@let null
            val cond = m.groupValues[2].trim()
            return if (cond.isNotEmpty()) {
                condSql(cond, tbl)?.let { Interpretation("SELECT * FROM $tbl WHERE $it", "Finding rows in $tbl") }
                    ?: DUNNO
            } else selectAll(tbl)
        }
        return null
    }

    private fun aggregation(clean: String): Interpretation? {
        RE_AGG.find(clean)?.let { m ->
            val aggWord = m.groupValues[1].lowercase()
            val rawField = m.groupValues[2].trim()
            val rawGroupBy = m.groupValues[3].trim()
            val fn = AGG_MAP[aggWord] ?: "AVG"

            if (rawGroupBy.isNotEmpty()) {
                if (!isKnownField(rawField) || !isKnownField(rawGroupBy)) return DUNNO
                return aggGrouped(rawGroupBy, rawField, fn, aggWord)
            }

            if (!isKnownField(rawField) && rawField.split(RE_WS).none { isKnownField(it) }) return DUNNO

            val textBefore = clean.substring(0, m.range.first).trim()
            val contextTable = textBefore.split(RE_WS).firstNotNullOfOrNull { resolveTable(it) }

            val tableFromField = rawField.split(RE_WS).lastOrNull { resolveTable(it) != null }
            val resolved = if (tableFromField != null) resolveField(tableFromField) else resolveField(rawField)
            if (resolved == null) return DUNNO

            if (contextTable != null && !resolved.first.equals(contextTable, ignoreCase = true)) return DUNNO

            val table = resolved.first
            val numericCol = pickNumericCol(table, resolved.second)

            val order = if (aggWord in ASC_WORDS) "ASC" else "DESC"
            return Interpretation(
                "SELECT * FROM $table ORDER BY $numericCol $order LIMIT 1",
                "Finding row with ${if (order == "ASC") "lowest" else "highest"} $numericCol"
            )
        }
        return null
    }

    private fun aggGrouped(rawGroupBy: String, rawField: String, fn: String, aggWord: String): Interpretation {
        val groupField = resolveField(rawGroupBy) ?: return DUNNO
        val aggField = resolveField(rawField) ?: return DUNNO
        val (gTable, gCol) = groupField
        val (aTable, aCol) = aggField

        if (!gTable.equals(aTable, ignoreCase = true)) {
            val gInfo = tableIndex[gTable.lowercase()]
            val aInfo = tableIndex[aTable.lowercase()]
            if (gInfo != null && aInfo != null) {
                val fk = resolveForeignKeyFromTo(aInfo, gInfo)
                if (fk != null) {
                    val (fkCol, refCol) = fk
                    val displayCol = gInfo.columns.firstOrNull { isText(it.type) }?.name ?: refCol
                    val alias = aTable.lowercase().first().toString()
                    return Interpretation(
                        "SELECT $gTable.$displayCol AS group_name, $fn($alias.$aCol) AS agg_value " +
                                "FROM $aTable $alias JOIN $gTable ON $alias.$fkCol = $gTable.$refCol " +
                                "GROUP BY $gTable.$displayCol ORDER BY agg_value DESC",
                        "${aggWord.replaceFirstChar { it.uppercase() }} $aCol grouped by $gTable.$displayCol"
                    )
                }
            }
            return DUNNO
        }

        val alias = aTable.lowercase().first().toString()
        return Interpretation(
            "SELECT $alias.$gCol AS group_name, $fn($alias.$aCol) AS agg_value " +
                    "FROM $aTable $alias GROUP BY $alias.$gCol ORDER BY agg_value DESC",
            "${aggWord.replaceFirstChar { it.uppercase() }} $aCol grouped by $gCol"
        )
    }

    private fun tableWhere(clean: String): Interpretation? {
        RE_TABLE_WHERE.find(clean)?.let { m ->
            val tbl = resolveTable(m.groupValues[1]) ?: return@let null
            return condSql(m.groupValues[2], tbl)?.let {
                Interpretation("SELECT * FROM $tbl WHERE $it", "Finding rows in $tbl")
            } ?: DUNNO
        }
        return null
    }

    private fun join(clean: String): Interpretation? {
        RE_JOIN.find(clean)?.let { m ->
            val t1 = resolveTable(m.groupValues[1])
            val t2 = resolveTable(m.groupValues[2])
            if (t1 != null && t2 != null) return buildJoin(t1, t2)
        }
        return null
    }

    private fun bareCondition(clean: String): Interpretation? {
        RE_BARE_COND.find(clean)?.let { m ->
            val (table, resolvedCol) = resolveField(m.groupValues[1]) ?: return null
            val op = m.groupValues[2]
            val value = m.groupValues[3].trim()
            val sqlOp = when (op) {
                "equals", "is" -> "="; "like" -> "LIKE"; else -> op
            }
            val sqlVal = if (value.toDoubleOrNull() != null || value.toIntOrNull() != null) value
            else "'${value.replace("'", "''")}'"
            val whereClause =
                if (sqlVal.startsWith("'")) "LOWER($resolvedCol) $sqlOp $sqlVal" else "$resolvedCol $sqlOp $sqlVal"
            return Interpretation(
                "SELECT * FROM $table WHERE $whereClause",
                "Finding rows where $resolvedCol $op $value"
            )
        }
        return null
    }

    // ---------- Private helpers ----------

    private fun selectAll(table: String) = Interpretation("SELECT * FROM $table", "Selecting all rows from $table")

    private fun describeTable(table: String) = Interpretation(
        "SELECT COLUMN_NAME AS column, DATA_TYPE AS type FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '$table'",
        "Showing structure of $table"
    )

    private fun condSql(condition: String, table: String): String? = resolveCondition(condition, table)?.first

    /** If the resolved column is a sensible choice use it; otherwise find a non-ID numeric column. */
    private fun pickNumericCol(table: String, resolvedCol: String): String {
        val info = tableIndex[table.lowercase()] ?: return resolvedCol
        // Already a good column — not the first column (likely ID fallback)
        if (resolvedCol != info.columns.first().name) return resolvedCol
        // Resolved to first column (likely ID) — try to find a better one
        return info.columns
            .filter { isNum(it.type) }
            .firstOrNull { !it.name.equals("id", ignoreCase = true) && !it.name.lowercase().endsWith("_id") }
            ?.name ?: resolvedCol
    }

    private fun resolveTable(name: String): String? {
        val key = name.trim().lowercase()
        tableIndex[key]?.let { return it.name }
        (tableIndex["${key}s"])?.let { return it.name }
        val singular = key.removeSuffix("es").removeSuffix("s")
        if (singular != key) tableIndex[singular]?.let { return it.name }
        if (key.endsWith("ies")) {
            tableIndex[key.removeSuffix("ies") + "y"]?.let { return it.name }
        }
        return null
    }

    /** Resolve a field name to (canonicalTable, canonicalColumn), or null if unknown. */
    private fun resolveField(name: String): Pair<String, String>? {
        val key = name.trim().lowercase()
        for (table in schema) {
            for (col in table.columns) {
                if (col.name.lowercase() == key) return table.name to col.name
            }
        }
        resolveTable(key)?.let { tableName ->
            val info = tableIndex[tableName.lowercase()]!!
            val numCol = info.columns
                .filter { isNum(it.type) }
                .firstOrNull { !it.name.equals("id", ignoreCase = true) && !it.name.lowercase().endsWith("_id") }
                ?: info.columns.firstOrNull { isNum(it.type) }
            return info.name to (numCol?.name ?: info.columns.first().name)
        }
        columnSynonyms[key]?.let { colName ->
            for (table in schema) {
                for (col in table.columns) {
                    if (col.name == colName) return table.name to col.name
                }
            }
        }
        columnIndex.entries.firstOrNull { (cn, _) -> cn.startsWith(key) || key.startsWith(cn) }
            ?.let { return it.value.first() }
        return null
    }

    /**
     * Parse a condition string for a given table, returning (sqlClause, displayText)
     * or null when the condition cannot be understood.
     */
    private fun resolveCondition(condition: String, table: String): Pair<String, String>? {
        val clean = condition.trim().lowercase()

        RE_OP.find(clean)?.let { m ->
            val fieldName = m.groupValues[1].trim()
            if (!isKnownField(fieldName)) return null
            val field = resolveField(fieldName)?.second ?: return null
            val op = m.groupValues[2]
            val value = m.groupValues[3].trim()
            val sqlValue = if (value.toDoubleOrNull() != null) value else "'${value.replace("'", "''")}'"
            val fieldForClause = if (sqlValue.startsWith("'")) "LOWER($field)" else field
            return "$fieldForClause $op $sqlValue" to "$field $op $value"
        }
        RE_BARE_EQ.find(clean)?.let { m ->
            val fieldName = m.groupValues[1].trim()
            if (!isKnownField(fieldName)) return null
            val field = resolveField(fieldName)?.second ?: return null
            return "$field = ${m.groupValues[2]}" to "$field = ${m.groupValues[2]}"
        }
        // "field text_value" — implicit =, case-insensitive
        RE_BARE_TEXT.find(clean)?.let { m ->
            val fieldName = m.groupValues[1].trim()
            if (!isKnownField(fieldName)) return null
            val field = resolveField(fieldName)?.second ?: return null
            val value = m.groupValues[2].trim()
            return "LOWER($field) = '$value'" to "$field = '$value'"
        }

        knownValues[clean]?.let { known ->
            val (tbl, col) = known.firstOrNull { it.first.equals(table, ignoreCase = true) } ?: known.first()
            if (!tbl.equals(table, ignoreCase = true)) {
                val qInfo = tableIndex[table.lowercase()]
                val vInfo = tableIndex[tbl.lowercase()]
                if (qInfo != null && vInfo != null) {
                    resolveForeignKeyFromTo(qInfo, vInfo)?.let { (fkCol, refCol) ->
                        return "$fkCol = (SELECT $refCol FROM $tbl WHERE LOWER($col) = '$clean')" to "$col = '$clean'"
                    }
                }
            }
            resolveForeignKey(tbl, col)?.let { (refTable, refCol, fkCol) ->
                return "$fkCol = (SELECT $refCol FROM $refTable WHERE LOWER($refCol) = '$clean')" to "$col = '$clean'"
            }
            return "$col = '${clean.replace("'", "''")}'" to "$col = '$clean'"
        }

        if (!clean.contains(" ")) {
            tableIndex[table.lowercase()]
                ?.columns?.firstOrNull { isText(it.type) }
                ?.let { return "LOWER(${it.name}) = '$clean'" to clean }
        }

        return null
    }

    private fun resolveForeignKey(table: String, column: String): Triple<String, String, String>? {
        val lower = column.lowercase()
        val refName = when {
            lower.endsWith("_id") -> lower.removeSuffix("_id")
            lower.endsWith("id") && lower.length > 2 -> lower.removeSuffix("id")
            else -> return null
        }
        val refTable = tableIndex[refName] ?: tableIndex["${refName}s"] ?: return null
        val idCol = refTable.columns.firstOrNull { it.name.lowercase() == "id" } ?: refTable.columns.first()
        return Triple(refTable.name, idCol.name, column)
    }

    private fun buildJoin(t1: String, t2: String): Interpretation? {
        val t1Info = tableIndex[t1.lowercase()] ?: return null
        val t2Info = tableIndex[t2.lowercase()] ?: return null

        resolveForeignKeyFromTo(t1Info, t2Info)?.let { (fkCol, refCol) ->
            val t2Cols = t2Info.columns.filter { it.name.lowercase() != refCol.lowercase() }
                .joinToString(", ") { "$t2.${it.name} AS ${t2.lowercase()}_${it.name.lowercase()}" }
            return Interpretation(
                "SELECT a.*, $t2Cols FROM $t1 a JOIN $t2 ON a.$fkCol = $t2.$refCol",
                "Joining $t1 and $t2 on $fkCol"
            )
        }
        resolveForeignKeyFromTo(t2Info, t1Info)?.let { (fkCol, refCol) ->
            val t1Cols = t1Info.columns.filter { it.name.lowercase() != refCol.lowercase() }
                .joinToString(", ") { "$t1.${it.name} AS ${t1.lowercase()}_${it.name.lowercase()}" }
            return Interpretation(
                "SELECT b.*, $t1Cols FROM $t2 b JOIN $t1 ON b.$fkCol = $t1.$refCol",
                "Joining $t2 and $t1 on $fkCol"
            )
        }

        return Interpretation("SELECT * FROM $t1, $t2", "Cross-joining $t1 and $t2 (no FK found)")
    }

    private fun isKnownField(name: String): Boolean {
        val key = name.trim().lowercase()
        return columnIndex.containsKey(key) || resolveTable(key) != null || columnSynonyms.containsKey(key)
    }

    private fun resolveForeignKeyFromTo(
        from: DatabaseManager.TableInfo,
        to: DatabaseManager.TableInfo
    ): Pair<String, String>? {
        val toSingular = to.name.lowercase().removeSuffix("es").removeSuffix("s")
        for (col in from.columns) {
            val lower = col.name.lowercase()
            if (lower.endsWith("_id") && lower.removeSuffix("_id") in listOf(toSingular, to.name.lowercase())) {
                val idCol = to.columns.firstOrNull { it.name.lowercase() == "id" } ?: to.columns.first()
                return col.name to idCol.name
            }
        }
        return null
    }

    private fun buildColumnSynonyms(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (table in schema) {
            for (col in table.columns) {
                val lower = col.name.lowercase()
                map[lower] = col.name
                val stripped = lower.removeSuffix("_id").removeSuffix("_fk")
                if (stripped != lower) map[stripped] = col.name
                map[lower.replace("_", " ")] = col.name
                map[lower.replace("_", "")] = col.name
            }
        }
        // Language-agnostic aliases (not data, so fine to hardcode)
        map["wage"] = map["salary"] ?: "salary"
        map["income"] = map["salary"] ?: "salary"
        map["plat"] = map["salary"] ?: "salary"
        map["jmeno"] = map["name"] ?: "name"
        map["oddeleni"] = map["department_id"] ?: map["department"] ?: "department_id"
        map["cena"] = map["price"] ?: "price"
        map["cost"] = map["price"] ?: "price"
        map["skladem"] = map["stock"] ?: "stock"
        map["quantity"] = map["stock"] ?: "stock"
        map["kategorie"] = map["category"] ?: "category"
        map["misto"] = map["location"] ?: "location"
        map["city"] = map["location"] ?: "location"
        map["nastup"] = map["hire_date"] ?: "hire_date"
        map["datum"] = map["date"] ?: "date"
        return map
    }

    companion object {
        private val RE_WS = Regex("\\s+")
        private val RE_BARE_WORD = Regex("^\\w+$")

        private val RE_SHOW = Regex("(?:show|display|list|all|select|zobraz|vsechny)\\s+(.+?)(?:\\s+table)?$")
        private val RE_DESC = Regex("(?:describe|structure|schema|info)\\s+(.+?)(?:\\s+table)?$")
        private val RE_COUNT = Regex("(?:how many|count|pocet|kolik)\\s+(.+?)(?:\\s+in\\s+(.+))?$")
        private val RE_FIND =
            Regex("(?:find|search|select|get|where|najdi|vyhledej|uka?z)\\s+(.+?)(?:\\s+(?:in|from|where|with|that|kde|s|v|ve|z)\\s+(.+))?$")
        private val RE_AGG =
            Regex("(average|avg|prumer|sum|soucet|total|min|max|minimum|maximum|nejmensi|nejvetsi|nejlevnejsi|nejdrazsi|cheapest|most cheap|most expensive|lowest|highest|top)\\s+(.+?)(?:\\s+(?:by|per|podle|dle)\\s+(.+))?$")
        private val RE_TABLE_WHERE = Regex("^(\\w+)\\s+(?:in|from|v|z|with|s|kde|where)\\s+(.+)$")
        private val RE_JOIN = Regex("(?:join|spoj)\\s+(\\w+)\\s+(?:and|with|a|s)\\s+(\\w+)")
        private val RE_BARE_COND = Regex("^(\\w+)\\s*(>|<|>=|<=|=|!=|equals|is|like)\\s*(.+)$")

        private val RE_OP = Regex("^(\\w+)\\s*(>|<|>=|<=|=|!=)\\s*(.+)$")
        private val RE_BARE_EQ = Regex("^(\\w+)\\s+(\\d+)$")
        private val RE_BARE_TEXT = Regex("^(\\w+)\\s+(.+)$")

        private val SHOW_TABLES = setOf("show tables", "list tables", "tables")
        private val HELP_WORDS = setOf("help", "prompts", "examples", "commands", "napoveda", "priklady")

        private val AGG_MAP = mapOf(
            "average" to "AVG", "avg" to "AVG", "prumer" to "AVG",
            "sum" to "SUM", "soucet" to "SUM", "total" to "SUM",
            "min" to "MIN", "minimum" to "MIN", "nejmensi" to "MIN",
            "nejlevnejsi" to "MIN", "most cheap" to "MIN", "cheapest" to "MIN",
            "lowest" to "MIN",
            "max" to "MAX", "maximum" to "MAX", "nejvetsi" to "MAX",
            "nejdrazsi" to "MAX", "most expensive" to "MAX", "top" to "MAX",
            "highest" to "MAX"
        )
        private val ASC_WORDS = setOf(
            "min", "minimum", "nejmensi", "nejlevnejsi",
            "cheapest", "most cheap", "lowest"
        )

        private val DUNNO = Interpretation("", "I didn't understand")

        private fun isText(type: String): Boolean = type.lowercase().let { t ->
            t.contains("varchar") || t.contains("char") || t.contains("text")
        }

        private fun isNum(type: String): Boolean = type.lowercase().let { t ->
            t.contains("int") || t.contains("decimal") || t.contains("numeric") ||
                    t.contains("float") || t.contains("double") || t.contains("real")
        }
    }
}
