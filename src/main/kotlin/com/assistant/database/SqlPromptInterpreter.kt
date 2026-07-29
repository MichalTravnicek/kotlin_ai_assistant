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
        schema.flatMap { t ->
            t.columns.filter { isText(it.type) }.flatMap { c ->
                valueQuery(t.name, c.name).map { it.lowercase().trim() to (t.name to c.name) }
            }
        }.groupBy({ it.first }) { it.second }
    }

    fun interpret(prompt: String): Interpretation {
        val clean = prompt.trim().lowercase().replace(RE_WS, " ")

        if (clean.startsWith("select")) return Interpretation(prompt, "Raw SQL passthrough")
        if (clean in SHOW_TABLES) return Interpretation("SHOW TABLES", "Showing all available tables")
        if (clean in HELP_WORDS) return Interpretation(
            "",
            "Available tables: ${schema.joinToString(", ") { it.name }}. Try: show <table>, find <table> with <condition>"
        )

        show(clean)?.let { return it }
        if (clean.matches(RE_BARE_WORD)) bareTable(clean)?.let { return it }
        describe(clean)?.let { return it }
        tableCond(clean, RE_COUNT, "COUNT(*) AS count")?.let { return it }
        tableCond(clean, RE_FIND, "*")?.let { return it }
        aggregation(clean)?.let { return it }
        tableWhere(clean)?.let { return it }
        join(clean)?.let { return it }
        bareCondition(clean)?.let { return it }

        return DUNNO
    }

    // ---------- Sub-interpreters (return null = no match) ----------

    private fun show(clean: String): Interpretation? = matchResolve(RE_SHOW, clean) { selectAll(it) }

    private fun bareTable(clean: String): Interpretation? = resolveTable(clean)?.let { selectAll(it) }

    private fun describe(clean: String): Interpretation? = matchResolve(RE_DESC, clean) { describeTable(it) }

    /** COUNT / FIND pattern: resolve table, optionally apply a condition. */
    private fun tableCond(clean: String, re: Regex, selectExpr: String): Interpretation? {
        re.find(clean)?.let { m ->
            val tbl = resolveTable(m.groupValues[1]) ?: return@let null
            val cond = m.groupValues[2].trim()
            return if (cond.isNotEmpty()) condSql(cond, tbl)?.let { sql ->
                Interpretation("SELECT $selectExpr FROM $tbl WHERE $sql", "Finding rows in $tbl")
            } ?: DUNNO else selectAll(tbl)
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
            val contextTable = clean.substring(0, m.range.first).trim()
                .split(RE_WS).firstNotNullOfOrNull { resolveTable(it) }
            val resolved = rawField.split(RE_WS).lastOrNull { resolveTable(it) != null }
                ?.let { resolveField(it) } ?: resolveField(rawField) ?: return DUNNO
            if (contextTable != null && !resolved.first.equals(contextTable, ignoreCase = true)) return DUNNO

            val col = resolved.second
            val order = if (aggWord in ASC_WORDS) "ASC" else "DESC"
            return Interpretation(
                "SELECT * FROM ${resolved.first} ORDER BY $col $order LIMIT 1",
                "Finding row with ${if (order == "ASC") "lowest" else "highest"} $col"
            )
        }
        return null
    }

    private fun aggGrouped(groupBy: String, field: String, fn: String, aggWord: String): Interpretation {
        val (gTable, gCol) = resolveField(groupBy) ?: return DUNNO
        val (aTable, aCol) = resolveField(field) ?: return DUNNO

        val (groupExpr, fromClause, groupLabel) = if (gTable.equals(aTable, ignoreCase = true)) {
            val alias = aTable.lowercase().first().toString()
            Triple("$alias.$gCol", "FROM $aTable $alias", gCol)
        } else {
            val gInfo = tableIndex[gTable.lowercase()] ?: return DUNNO
            val aInfo = tableIndex[aTable.lowercase()] ?: return DUNNO
            val (fkCol, refCol) = resolveForeignKeyFromTo(aInfo, gInfo) ?: return DUNNO
            val displayCol = gInfo.columns.firstOrNull { isText(it.type) }?.name ?: refCol
            val alias = aTable.lowercase().first().toString()
            Triple(
                "$gTable.$displayCol",
                "FROM $aTable $alias JOIN $gTable ON $alias.$fkCol = $gTable.$refCol",
                "$gTable.$displayCol"
            )
        }
        val aAlias = aTable.lowercase().first().toString()
        return Interpretation(
            "SELECT $groupExpr AS group_name, $fn($aAlias.$aCol) AS agg_value $fromClause GROUP BY $groupExpr ORDER BY agg_value DESC",
            "${aggWord.replaceFirstChar { it.uppercase() }} $aCol grouped by $groupLabel"
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
            val sqlVal = quoteVal(value)
            val col = if (isQuoted(sqlVal)) "LOWER($resolvedCol)" else resolvedCol
            return Interpretation(
                "SELECT * FROM $table WHERE $col $sqlOp $sqlVal",
                "Finding rows where $resolvedCol $op $value"
            )
        }
        return null
    }

    // ---------- Helpers ----------

    private fun selectAll(table: String) = Interpretation("SELECT * FROM $table", "Selecting all rows from $table")

    private fun describeTable(table: String) = Interpretation(
        "SELECT COLUMN_NAME AS column, DATA_TYPE AS type FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '$table'",
        "Showing structure of $table"
    )

    /** Shorthand: match regex that captures a table name, resolve, then map. */
    private fun matchResolve(re: Regex, input: String, f: (String) -> Interpretation): Interpretation? {
        re.find(input)?.let { m -> resolveTable(m.groupValues[1])?.let { return f(it) } }
        return null
    }

    private fun condSql(condition: String, table: String): String? = resolveCondition(condition, table)?.first

    /** Like resolveField but returns only the column name, null if unknown. */
    private fun resolveColumn(name: String): String? {
        return if (isKnownField(name)) resolveField(name)?.second else null
    }

    private fun resolveTable(name: String): String? {
        val key = name.trim().lowercase()
        tableIndex[key]?.let { return it.name }
        (tableIndex["${key}s"])?.let { return it.name }
        val singular = key.removeSuffix("es").removeSuffix("s")
        if (singular != key) tableIndex[singular]?.let { return it.name }
        if (key.endsWith("ies")) tableIndex[key.removeSuffix("ies") + "y"]?.let { return it.name }
        return null
    }

    /** Resolve a field name to (canonicalTable, canonicalColumn), or null if unknown. */
    private fun resolveField(name: String): Pair<String, String>? {
        val key = name.trim().lowercase()
        for (table in schema) for (col in table.columns) {
            if (col.name.lowercase() == key) return table.name to col.name
        }
        resolveTable(key)?.let { tableName ->
            val info = tableIndex[tableName.lowercase()]!!
            val numCol = info.columns.filter { isNum(it.type) }
                .firstOrNull { !it.name.equals("id", ignoreCase = true) && !it.name.lowercase().endsWith("_id") }
                ?: info.columns.firstOrNull { isNum(it.type) }
            return info.name to (numCol?.name ?: info.columns.first().name)
        }
        columnSynonyms[key]?.let { colName ->
            for (table in schema) for (col in table.columns) {
                if (col.name == colName) return table.name to col.name
            }
        }
        columnIndex.entries.firstOrNull { (cn, _) -> cn.startsWith(key) || key.startsWith(cn) }
            ?.let { return it.value.first() }
        return null
    }

    /** Parse a condition, returning (sqlClause, displayText) or null if not understood. */
    private fun resolveCondition(condition: String, table: String): Pair<String, String>? {
        val clean = condition.trim().lowercase()

        // "field op value" or "field value" (implicit =)
        val opMatch = RE_OP.find(clean)
        if (opMatch != null) {
            val field = resolveColumn(opMatch.groupValues[1].trim()) ?: return null
            val op = opMatch.groupValues[2]
            val value = opMatch.groupValues[3].trim()
            val sqlVal = quoteVal(value)
            return "${cmpField(field, sqlVal)} $op $sqlVal" to "$field $op $value"
        }
        RE_BARE_EQ_OR_TEXT.find(clean)?.let { m ->
            val field = resolveColumn(m.groupValues[1].trim()) ?: return null
            val value = m.groupValues[2].trim()
            val sqlVal = quoteVal(value)
            return "${cmpField(field, sqlVal)} = $sqlVal" to "$field = $value"
        }

        knownValues[clean]?.let { known ->
            val (tbl, col) = known.firstOrNull { it.first.equals(table, ignoreCase = true) } ?: known.first()
            if (!tbl.equals(table, ignoreCase = true)) {
                resolveForeignKeyFromTo(
                    tableIndex[table.lowercase()] ?: return null,
                    tableIndex[tbl.lowercase()] ?: return null
                )?.let { (fkCol, refCol) ->
                    return "$fkCol = (SELECT $refCol FROM $tbl WHERE LOWER($col) = '$clean')" to "$col = '$clean'"
                }
            }
            resolveForeignKey(tbl, col)?.let { (refTable, refCol, fkCol) ->
                return "$fkCol = (SELECT $refCol FROM $refTable WHERE LOWER($refCol) = '$clean')" to "$col = '$clean'"
            }
            return "$col = '${clean.replace("'", "''")}'" to "$col = '$clean'"
        }

        if (!clean.contains(" ")) {
            tableIndex[table.lowercase()]?.columns?.firstOrNull { isText(it.type) }
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
        return buildJoinFk(t1Info, t2Info, "a", t1, t2)
            ?: buildJoinFk(t2Info, t1Info, "b", t2, t1)
            ?: Interpretation("SELECT * FROM $t1, $t2", "Cross-joining $t1 and $t2 (no FK found)")
    }

    /** Build a JOIN SQL from `from` table FK-referencing `to` table, or null if no FK. */
    private fun buildJoinFk(
        from: DatabaseManager.TableInfo,
        to: DatabaseManager.TableInfo,
        alias: String,
        fromName: String,
        toName: String
    ): Interpretation? {
        resolveForeignKeyFromTo(from, to)?.let { (fkCol, refCol) ->
            val toCols = to.columns.filter { it.name.lowercase() != refCol.lowercase() }
                .joinToString(", ") { "$toName.${it.name} AS ${toName.lowercase()}_${it.name.lowercase()}" }
            return Interpretation(
                "SELECT $alias.*, $toCols FROM $fromName $alias JOIN $toName ON $alias.$fkCol = $toName.$refCol",
                "Joining $fromName and $toName on $fkCol"
            )
        }
        return null
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
        for (table in schema) for (col in table.columns) {
            val lower = col.name.lowercase()
            map[lower] = col.name
            val stripped = lower.removeSuffix("_id").removeSuffix("_fk")
            if (stripped != lower) map[stripped] = col.name
            map[lower.replace("_", " ")] = col.name
            map[lower.replace("_", "")] = col.name
        }
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
        private val AGG_GROUPS = mapOf(
            "AVG" to listOf("average", "avg", "prumer"),
            "SUM" to listOf("sum", "soucet", "total"),
            "MIN" to listOf("min", "minimum", "nejmensi", "nejlevnejsi", "most cheap", "cheapest", "lowest"),
            "MAX" to listOf("max", "maximum", "nejvetsi", "nejdrazsi", "most expensive", "top", "highest")
        )
        private val AGG_MAP = AGG_GROUPS.flatMap { (fn, words) -> words.map { it to fn } }.toMap()

        private val RE_AGG = Regex(
            "(" + AGG_MAP.keys.joinToString("|") { Regex.escape(it) } + ")\\s+(.+?)(?:\\s+(?:by|per|podle|dle)\\s+(.+))?$"
        )

        private val RE_TABLE_WHERE = Regex("^(\\w+)\\s+(?:in|from|v|z|with|s|kde|where)\\s+(.+)$")
        private val RE_JOIN = Regex("(?:join|spoj)\\s+(\\w+)\\s+(?:and|with|a|s)\\s+(\\w+)")
        private val RE_BARE_COND = Regex("^(\\w+)\\s*(>|<|>=|<=|=|!=|equals|is|like)\\s*(.+)$")
        private val RE_OP = Regex("^(\\w+)\\s*(>|<|>=|<=|=|!=)\\s*(.+)$")
        private val RE_BARE_EQ_OR_TEXT = Regex("^(\\w+)\\s+(.+)$")

        private val SHOW_TABLES = setOf("show tables", "list tables", "tables")
        private val HELP_WORDS = setOf("help", "prompts", "examples", "commands", "napoveda", "priklady")

        private val ASC_WORDS = AGG_MAP.filterValues { it == "MIN" }.keys

        private val DUNNO = Interpretation("", "I didn't understand")

        /** Quote a value for SQL — pass numerics through, quote strings with escape. */
        private fun quoteVal(v: String): String {
            // check if value contains only digits/dots/minus (unstripped, no leading/trailing quote)
            val t = v.trim()
            return if (t.toDoubleOrNull() != null) t else "'${t.replace("'", "''")}'"
        }

        /** True when the SQL value is a quoted string (needs case-insensitive comparison). */
        private fun isQuoted(v: String) = v.startsWith("'")

        /** Wrap field in LOWER() when comparing against a string. */
        private fun cmpField(f: String, v: String) = if (isQuoted(v)) "LOWER($f)" else f

        private fun isText(type: String) =
            type.lowercase().let { it.contains("varchar") || it.contains("char") || it.contains("text") }

        private fun isNum(type: String) = type.lowercase().let {
            it.contains("int") || it.contains("decimal") || it.contains("numeric") || it.contains("float") || it.contains(
                "double"
            ) || it.contains("real")
        }
    }
}
