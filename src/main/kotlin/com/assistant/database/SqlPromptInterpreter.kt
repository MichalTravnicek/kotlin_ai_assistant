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
    data class Interpretation(
        val sql: String,
        val explanation: String,
//        val isSelect: Boolean
    )

    private val schema: List<DatabaseManager.TableInfo> by lazy { schemaProvider() }

    // tableName.lowercase() -> actual table data
    private val tableIndex: Map<String, DatabaseManager.TableInfo> by lazy {
        schema.associateBy { it.name.lowercase() }
    }

    // columnName.lowercase() -> list of (tableName, actualColumnName)
    private val columnIndex: Map<String, List<Pair<String, String>>> by lazy {
        schema.flatMap { table ->
            table.columns.map { col ->
                col.name.lowercase() to (table.name to col.name)
            }
        }.groupBy({ it.first }) { it.second }
    }

    // Column synonyms built dynamically from schema
    private val columnSynonyms: Map<String, String> by lazy {
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
        // Common CZ/EN aliases (language-agnostic, not data)
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
        map
    }

    // Distinct text values from the DB -> (tableName, columnName)
    private val knownValues: Map<String, List<Pair<String, String>>> by lazy {
        val map = mutableMapOf<String, MutableList<Pair<String, String>>>()
        for (table in schema) {
            for (col in table.columns) {
                if (isTextColumn(col.type)) {
                    val results = valueQuery(table.name, col.name)
                    for (value in results) {
                        val key = value.lowercase().trim()
                        map.getOrPut(key) { mutableListOf() }
                            .add(table.name to col.name)
                    }
                }
            }
        }
        map
    }

    fun interpret(prompt: String): Interpretation {
        val clean = prompt.trim().lowercase().replace(Regex("\\s+"), " ")

        // 1. Raw custom SQL passthrough
        if (clean.startsWith("select")) {
            return Interpretation(prompt, "Raw SQL passthrough")
        }

        // 2. SHOW TABLES
        if (clean.matches(Regex("show\\s+tables|list\\s+tables|tables"))) {
            return Interpretation("SHOW TABLES", "Showing all available tables")
        }

        // 3. HELP
        if (clean.matches(Regex("help|prompts|examples|commands|napoveda|priklady"))) {
            val tables = schema.joinToString(", ") { it.name }
            return Interpretation(
                "", "Available tables: $tables. Try: show <table>, find <table> with <condition>"
            )
        }

        // 4. SHOW <table>
        val showMatch = Regex("(?:show|display|list|all|select|zobraz|vsechny)\\s+(.+?)(?:\\s+table)?$").find(clean)
        if (showMatch != null) {
            val tbl = resolveTable(showMatch.groupValues[1])
            if (tbl != null) return selectAll(tbl)
        }

        // 5. DESCRIBE <table>
        val descMatch = Regex("(?:describe|structure|schema|info)\\s+(.+?)(?:\\s+table)?$").find(clean)
        if (descMatch != null) {
            val tbl = resolveTable(descMatch.groupValues[1])
            if (tbl != null) return describeTable(tbl)
        }

        // 6. COUNT
        val countMatch = Regex("(?:how many|count|pocet|kolik)\\s+(.+?)(?:\\s+in\\s+(.+))?$").find(clean)
        if (countMatch != null) {
            val what = countMatch.groupValues[1].trim()
            val condition = countMatch.groupValues[2].trim()
            val tbl = resolveTable(what)
            if (tbl != null) {
                return if (condition.isNotEmpty()) {
                    val (colSql, _) = resolveCondition(condition, tbl)
                    Interpretation(
                        "SELECT COUNT(*) AS count FROM $tbl WHERE $colSql",
                        "Counting filtered rows in $tbl",
                    )
                } else {
                    Interpretation("SELECT COUNT(*) AS count FROM $tbl", "Counting all rows in $tbl")
                }
            }
        }

        // 7. FIND <table> <condition>
        val findMatch =
            Regex("(?:find|search|select|get|where|najdi|vyhledej|uka?z)\\s+(.+?)(?:\\s+(?:in|from|where|with|that|kde|s|v|ve|z)\\s+(.+))?$").find(
                clean
            )
        if (findMatch != null) {
            val target = findMatch.groupValues[1].trim()
            val condition = findMatch.groupValues[2].trim()
            val tbl = resolveTable(target)
            if (tbl != null) {
                return if (condition.isNotEmpty()) {
                    val (colSql, _) = resolveCondition(condition, tbl)
                    Interpretation("SELECT * FROM $tbl WHERE $colSql", "Finding rows in $tbl")
                } else selectAll(tbl)
            }
        }

        // 8. Aggregation (before simpleWhereMatch so "employee with lowest salary" hits agg, not WHERE)
        val aggMatch =
            Regex("(average|avg|prumer|sum|soucet|total|min|max|minimum|maximum|nejmensi|nejvetsi|nejlevnejsi|nejdrazsi|cheapest|most cheap|most expensive|lowest|highest|top)\\s+(.+?)(?:\\s+(?:by|per|podle|dle)\\s+(.+))?$").find(
                clean
            )
        if (aggMatch != null) {
            val aggFunc = aggMatch.groupValues[1].lowercase()
            val rawField = aggMatch.groupValues[2].trim()
            val rawGroupBy = aggMatch.groupValues[3].trim()

            val fn = when (aggFunc) {
                in listOf("average", "avg", "prumer") -> "AVG"
                in listOf("sum", "soucet", "total") -> "SUM"
                in listOf("min", "minimum", "nejmensi", "nejlevnejsi", "most cheap", "cheapest", "lowest") -> "MIN"
                in listOf("max", "maximum", "nejvetsi", "nejdrazsi", "most expensive", "top", "highest") -> "MAX"
                else -> "AVG"
            }

            if (rawGroupBy.isNotEmpty()) {
                val groupField = resolveField(rawGroupBy)
                val aggField = resolveField(rawField)
                val (gTable, gCol) = groupField
                val (aTable, aCol) = aggField

                // Detect cross-table reference
                if (!gTable.equals(aTable, ignoreCase = true)) {
                    val gInfo = tableIndex[gTable.lowercase()]
                    val aInfo = tableIndex[aTable.lowercase()]
                    if (gInfo != null && aInfo != null) {
                        val fk = resolveForeignKeyFromTo(aInfo, gInfo)
                        if (fk != null) {
                            val (fkCol, refCol) = fk
                            val displayCol = gInfo.columns.firstOrNull {
                                isTextColumn(it.type)
                            }?.name ?: refCol
                            val alias = aTable.lowercase().first().toString()
                            return Interpretation(
                                "SELECT $gTable.$displayCol AS group_name, $fn($alias.$aCol) AS agg_value FROM $aTable $alias JOIN $gTable ON $alias.$fkCol = $gTable.$refCol GROUP BY $gTable.$displayCol ORDER BY agg_value DESC",
                                "${aggFunc.replaceFirstChar { it.uppercase() }} $aCol grouped by $gTable.$displayCol"
                            )
                        }
                    }
                }

                val alias = aTable.lowercase().first().toString()
                return Interpretation(
                    "SELECT $alias.$gCol AS group_name, $fn($alias.$aCol) AS agg_value FROM $aTable $alias GROUP BY $alias.$gCol ORDER BY agg_value DESC",
                    "${aggFunc.replaceFirstChar { it.uppercase() }} $aCol grouped by $gCol"
                )
            } else {
                // Simple: min/max
                val tableFromField = rawField.split(Regex("\\s+")).lastOrNull { word -> resolveTable(word) != null }
                val resolved = if (tableFromField != null) resolveField(tableFromField) else resolveField(rawField)
                val table = resolved.first
                // Use the resolved column if exact; otherwise pick a sensible numeric col
                val numericCol = if (resolved.second != tableIndex[table.lowercase()]?.columns?.first()?.name) {
                    resolved.second
                } else {
                    tableIndex[table.lowercase()]
                        ?.columns?.filter { isNumeric(it.type) }
                        ?.firstOrNull {
                            !it.name.equals("id", ignoreCase = true) && !it.name.lowercase().endsWith("_id")
                        }
                        ?.name
                        ?: resolved.second
                }
                val order = if (aggFunc in listOf(
                        "min",
                        "minimum",
                        "nejmensi",
                        "nejlevnejsi",
                        "cheapest",
                        "most cheap",
                        "lowest"
                    )
                ) "ASC" else "DESC"
                return Interpretation(
                    "SELECT * FROM $table ORDER BY $numericCol $order LIMIT 1",
                    "Finding row with ${if (order == "ASC") "lowest" else "highest"} $numericCol"
                )
            }
        }

        // 9. "<table> with/in <condition>" — also catches agg patterns like "product with highest stock"
        val simpleWhereMatch = Regex("^(\\w+)\\s+(?:in|from|v|z|with|s|kde)\\s+(.+)$").find(clean)
        if (simpleWhereMatch != null) {
            val tbl = resolveTable(simpleWhereMatch.groupValues[1])
            val condition = simpleWhereMatch.groupValues[2].trim()
            if (tbl != null) {
                val (colSql, _) = resolveCondition(condition, tbl)
                return Interpretation("SELECT * FROM $tbl WHERE $colSql", "Finding rows in $tbl")
            }
        }

        // 10. JOIN
        val joinMatch = Regex("(?:join|spoj)\\s+(\\w+)\\s+(?:and|with|a|s)\\s+(\\w+)").find(clean)
        if (joinMatch != null) {
            val t1 = resolveTable(joinMatch.groupValues[1])
            val t2 = resolveTable(joinMatch.groupValues[2])
            if (t1 != null && t2 != null) {
                val join = buildJoin(t1, t2)
                if (join != null) return join
            }
        }

        // 11. Bare condition: "price > 50"
        val bareConditionMatch = Regex("^(\\w+)\\s*(>|<|>=|<=|=|!=|equals|is|like)\\s*(.+)$").find(clean)
        if (bareConditionMatch != null) {
            val field = bareConditionMatch.groupValues[1]
            val op = bareConditionMatch.groupValues[2]
            val value = bareConditionMatch.groupValues[3].trim()
            val (table, resolvedCol) = resolveField(field)
            val sqlOp = when (op) {
                "equals", "is" -> "="; "like" -> "LIKE"; else -> op
            }
            val sqlVal = if (value.toDoubleOrNull() != null || value.toIntOrNull() != null) value
            else "'${value.replace("'", "''")}'"
            return Interpretation(
                "SELECT * FROM $table WHERE $resolvedCol $sqlOp $sqlVal",
                "Finding rows where $resolvedCol $op $value"
            )
        }

        // 12. Fallback
        val tableList = schema.joinToString(", ") { it.name }
        return Interpretation(
            "SHOW TABLES",
            "I didn't understand. Tables: $tableList. Try: show <table>, find <table> with <condition>, join <t1> and <t2>"
        )
    }

    // -------- Private helpers --------

    private fun selectAll(table: String) =
        Interpretation("SELECT * FROM $table", "Selecting all rows from $table")

    private fun describeTable(table: String): Interpretation {
        return Interpretation(
            "SELECT COLUMN_NAME AS column, DATA_TYPE AS type FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '$table'",
            "Showing structure of $table"
        )
    }

    private fun resolveTable(name: String): String? {
        val key = name.trim().lowercase()
        tableIndex[key]?.let { return it.name }
        // Singular -> plural
        val plural = "${key}s"
        tableIndex[plural]?.let { return it.name }
        // Plural -> singular
        val singular = key.removeSuffix("es").removeSuffix("s")
        if (singular != key) tableIndex[singular]?.let { return it.name }
        // IES -> Y
        if (key.endsWith("ies")) {
            val ies = key.removeSuffix("ies") + "y"
            tableIndex[ies]?.let { return it.name }
        }
        return null
    }

    private fun resolveField(name: String): Pair<String, String> {
        val key = name.trim().lowercase()
        // Exact column match
        for (table in schema) {
            for (col in table.columns) {
                if (col.name.lowercase() == key) return table.name to col.name
            }
        }
        // It's a table name -> first numeric col (before column synonyms, so "product" -> products.price)
        val tableName = resolveTable(key)
        if (tableName != null) {
            val info = tableIndex[tableName.lowercase()]!!
            val numCol = info.columns
                .filter { isNumeric(it.type) }
                .firstOrNull { !it.name.equals("id", ignoreCase = true) && !it.name.lowercase().endsWith("_id") }
                ?: info.columns.firstOrNull { isNumeric(it.type) }
            return info.name to (numCol?.name ?: info.columns.first().name)
        }
        // Synonym match
        columnSynonyms[key]?.let { colName ->
            for (table in schema) {
                for (col in table.columns) {
                    if (col.name == colName) return table.name to col.name
                }
            }
        }
        // Fuzzy column match
        columnIndex.entries.firstOrNull { (colName, _) ->
            colName.startsWith(key) || key.startsWith(colName)
        }?.let { (_, tables) ->
            return tables.first()
        }
        // Last resort
        val first = schema.first()
        return first.name to first.columns.first().name
    }

    private fun resolveCondition(condition: String, table: String): Pair<String, String> {
        val clean = condition.trim().lowercase()

        // "field > value"
        val opMatch = Regex("^(\\w+)\\s*(>|<|>=|<=|=|!=)\\s*(.+)$").find(clean)
        if (opMatch != null) {
            val field = resolveField(opMatch.groupValues[1].trim()).second
            val op = opMatch.groupValues[2]
            val value = opMatch.groupValues[3].trim()
            val sqlValue = if (value.toDoubleOrNull() != null) value else "'${value.replace("'", "''")}'"
            return "$field $op $sqlValue" to "$field $op $value"
        }

        // Known value from DB
        val known = knownValues[clean]
        if (known != null) {
            val (tbl, col) = known.firstOrNull { it.first.equals(table, ignoreCase = true) } ?: known.first()
            // If value is from a different table, find FK from query table to value table
            if (!tbl.equals(table, ignoreCase = true)) {
                val queryInfo = tableIndex[table.lowercase()]
                val valueInfo = tableIndex[tbl.lowercase()]
                if (queryInfo != null && valueInfo != null) {
                    val fk = resolveForeignKeyFromTo(queryInfo, valueInfo)
                    if (fk != null) {
                        val (fkCol, refCol) = fk
                        return "$fkCol = (SELECT $refCol FROM $tbl WHERE LOWER($col) = '$clean')" to "$col = '$clean'"
                    }
                }
            }
            val fkRef = resolveForeignKey(tbl, col)
            if (fkRef != null) {
                val (refTable, refCol, fkCol) = fkRef
                return "$fkCol = (SELECT $refCol FROM $refTable WHERE LOWER($refCol) = '$clean')" to "$col = '$clean'"
            }
            return "$col = '${clean.replace("'", "''")}'" to "$col = '$clean'"
        }

        // "is value"
        val isMatch = Regex("^(?:is|equals|like|je|=)\\s+(.+)$").find(clean)
        if (isMatch != null) {
            val value = isMatch.groupValues[1].trim()
            return "'${value.replace("'", "''")}' = '${value.replace("'", "''")}'" to "= '$value'"
        }

        return "? = '${clean.replace("'", "''")}'" to clean
    }

    private fun resolveForeignKey(table: String, column: String): Triple<String, String, String>? {
        val colLower = column.lowercase()
        val refName = when {
            colLower.endsWith("_id") -> colLower.removeSuffix("_id")
            colLower.endsWith("id") && colLower.length > 2 -> colLower.removeSuffix("id")
            else -> return null
        }
        val refTable = tableIndex[refName] ?: tableIndex["${refName}s"] ?: return null
        val idCol = refTable.columns.firstOrNull { it.name.lowercase() == "id" } ?: refTable.columns.first()
        return Triple(refTable.name, idCol.name, column)
    }

    private fun buildJoin(t1: String, t2: String): Interpretation? {
        val t1Info = tableIndex[t1.lowercase()] ?: return null
        val t2Info = tableIndex[t2.lowercase()] ?: return null

        // Try FK from t1 -> t2
        val fk12 = resolveForeignKeyFromTo(t1Info, t2Info)
        if (fk12 != null) {
            val (fkCol, refCol) = fk12
            val alias = "a"
            val t2Columns = t2Info.columns.filter { it.name.lowercase() != refCol.lowercase() }
                .joinToString(", ") { "$t2.${it.name} AS ${t2.lowercase()}_${it.name.lowercase()}" }
            val sql = "SELECT $alias.*, $t2Columns FROM $t1 $alias JOIN $t2 ON $alias.$fkCol = $t2.$refCol"
            return Interpretation(sql, "Joining $t1 and $t2 on $fkCol")
        }
        // Try FK from t2 -> t1
        val fk21 = resolveForeignKeyFromTo(t2Info, t1Info)
        if (fk21 != null) {
            val (fkCol, refCol) = fk21
            val alias = "b"
            val t1Columns = t1Info.columns.filter { it.name.lowercase() != refCol.lowercase() }
                .joinToString(", ") { "$t1.${it.name} AS ${t1.lowercase()}_${it.name.lowercase()}" }
            val sql = "SELECT $alias.*, $t1Columns FROM $t2 $alias JOIN $t1 ON $alias.$fkCol = $t1.$refCol"
            return Interpretation(sql, "Joining $t2 and $t1 on $fkCol")
        }

        return Interpretation(
            "SELECT * FROM $t1, $t2",
            "Cross-joining $t1 and $t2 (no FK found)"
        )
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

    companion object {
        private fun isTextColumn(type: String): Boolean {
            val t = type.lowercase()
            return t.contains("varchar") || t.contains("char") || t.contains("text")
        }

        private fun isNumeric(type: String): Boolean {
            val t = type.lowercase()
            return t.contains("int") || t.contains("decimal") || t.contains("numeric") ||
                    t.contains("float") || t.contains("double") || t.contains("real")
        }
    }
}
